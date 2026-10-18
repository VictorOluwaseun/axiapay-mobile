package com.kesofty.axiapay.data.http

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Adapter-only failure metadata; bodies, credentials and network diagnostics never cross layers.
 */
class ApiRequestFailure(
    val kind: ApiFailureKind,
    val status: Int? = null,
    val retryAfterSeconds: Long = 0,
    val requestReference: String? = null,
    val code: String? = null,
) : Exception("API request failed: $kind")

/**
 * Separates transport/configuration errors from HTTP business classifications owned by adapters.
 */
enum class ApiFailureKind {
    Configuration,
    Network,
    InvalidResponse,
    Http,
}

/** Shared bounded JSON transport; feature adapters own DTOs and map safe failure metadata. */
class ApiHttpClient(
    private val baseUrl: String,
    private val allowLocalHttp: Boolean,
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val requestIds: ApiRequestIdGenerator = ApiRequestIdGenerator(),
) {
    /** Stable API scope used by protected credential storage and all authenticated adapters. */
    val apiIdentity: String
        get() = endpoint().toString()

    /**
     * Permit explicit emulator/loopback HTTP only; never follow credentials to a supplied origin.
     */
    private fun endpoint(): HttpUrl {
        val url = baseUrl.toHttpUrlOrNull() ?: throw ApiRequestFailure(ApiFailureKind.Configuration)
        val local =
            allowLocalHttp &&
                url.scheme == "http" &&
                url.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
        val valid =
            (url.isHttps || local) &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.query == null &&
                url.fragment == null &&
                url.encodedPath.endsWith("/")
        if (!valid) {
            throw ApiRequestFailure(ApiFailureKind.Configuration)
        }
        return url
    }

    /**
     * Execute one uncached request off-main; cancellation closes its call and mutations never
     * replay.
     */
    suspend fun request(
        path: String,
        expected: Int,
        payload: JSONObject? = null,
        bearer: String? = null,
        query: Map<String, String> = emptyMap(),
    ): JSONObject? =
        withContext(io) {
            val url = endpoint().newBuilder().addPathSegments(path)
            query.forEach { (name, value) -> url.addQueryParameter(name, value) }
            val builder =
                Request.Builder()
                    .url(url.build())
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("Cache-Control", "no-store")
                    .header("Accept", "application/json")
                    .header("X-Request-Id", requestIds.next())
            if (payload != null) {
                builder.post(
                    payload
                        .toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
            }
            if (bearer != null) {
                builder.header("Authorization", "Bearer $bearer")
            }
            try {
                executeJson(builder.build(), expected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ApiRequestFailure) {
                throw failure
            } catch (failure: IOException) {
                throw ApiRequestFailure(ApiFailureKind.Network)
            } catch (failure: Exception) {
                throw ApiRequestFailure(ApiFailureKind.InvalidResponse)
            }
        }

    /**
     * Keep cancellation registered until the bounded body is consumed and closed, not just until
     * response headers arrive. OkHttp's callback thread owns blocking response reads.
     */
    private suspend fun executeJson(request: Request, expected: Int): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    /** Propagate transport failure to the owning coroutine for safe mapping. */
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    /**
                     * Close the response before publishing JSON; cancellation interrupts body IO.
                     */
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use { readJson(it, expected) }
                            continuation.resume(result)
                        } catch (failure: Exception) {
                            continuation.resumeWithException(failure)
                        }
                    }
                },
            )
        }

    /**
     * Read only a bounded machine code from an error response; never return upstream messages or
     * bodies.
     */
    private fun errorCode(response: Response): String? {
        try {
            val json = boundedJson(response)
            val code = json.opt("code") as? String ?: return null
            return code.takeIf { value ->
                value.length in 1..80 && value.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
            }
        } catch (_: Exception) {
            return null
        }
    }

    /** Validate HTTP metadata and read at most 64 KiB; never expose response bodies in errors. */
    private fun readJson(response: Response, expected: Int): JSONObject? {
        if (response.code != expected) {
            val retry =
                if (response.code == 429)
                    response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86400) ?: 60
                else 0
            val reference =
                response.header("X-Request-Id")?.takeIf {
                    isValidApiRequestId(it)
                }
            throw ApiRequestFailure(
                ApiFailureKind.Http,
                response.code,
                retry,
                reference,
                errorCode(response),
            )
        }
        if (expected == 204) {
            return null
        }
        return boundedJson(response)
    }

    /** Consume a JSON body under the same cancellation registration and fixed size limit. */
    private fun boundedJson(response: Response): JSONObject {
        val body = response.body
        if (body.contentType()?.subtype != "json") {
            throw ApiRequestFailure(ApiFailureKind.InvalidResponse)
        }
        val source = body.source()
        source.request(65537)
        if (source.buffer.size > 65536) {
            throw ApiRequestFailure(ApiFailureKind.InvalidResponse)
        }
        return JSONObject(source.readUtf8())
    }
}
