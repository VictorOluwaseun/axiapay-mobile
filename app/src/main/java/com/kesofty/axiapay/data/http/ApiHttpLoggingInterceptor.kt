package com.kesofty.axiapay.data.http

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Bounded, redacted HTTP diagnostics. Composition enables this only in dev debug builds. Logs
 * retain request/response structure and known public values; credentials, personal fields, money,
 * identifiers and unknown values are masked. Logging never retries or changes a request/response.
 */
class ApiHttpLoggingInterceptor(private val logger: (String) -> Unit) : Interceptor {
    /** Correlate a single request, status, elapsed time and safely inspected body under one ID. */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val id =
            request.header("X-Request-Id").takeIf(::isValidApiRequestId)
                ?: UUID.randomUUID().toString().take(8)
        val started = System.nanoTime()
        record(id, "--> ${request.method}") { address(request) }
        record(id, "request headers") { headers(request.headers) }
        record(id, "request body") { requestBody(request) }
        try {
            val response = chain.proceed(request)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            record(id, "<-- ${response.code} (${elapsedMs}ms)") { address(request) }
            record(id, "response headers") { headers(response.headers) }
            record(id, "response body") { responseBody(response) }
            return response
        } catch (failure: Exception) {
            record(id, "<-- HTTP failed") { failure.javaClass.simpleName }
            throw failure
        }
    }

    /** Exclude URL credentials/fragments and redact dynamic paths and private query values. */
    private fun address(request: Request): String {
        val url = request.url
        val path = url.pathSegments.joinToString("/") { if (it in publicPaths) it else REDACTED }
        val query = JSONObject()
        for (index in 0 until minOf(url.querySize, MAX_FIELDS)) {
            val key = url.queryParameterName(index)
            query.put(key, publicValue(key, url.queryParameterValue(index)))
        }
        return "${url.scheme}://${url.host}:${url.port}/$path query=$query"
    }

    /** Preserve header names while masking cookies, bearer credentials and unknown values. */
    private fun headers(headers: Headers): String {
        val result = JSONArray()
        for (index in 0 until minOf(headers.size, MAX_FIELDS)) {
            val name = headers.name(index)
            val value = headers.value(index)
            val visible =
                when {
                    name.equals("X-Request-Id", true) && isValidApiRequestId(value) -> value
                    name.equals("Content-Type", true) &&
                        value.substringBefore(';').trim() in publicContentTypes ->
                        value.substringBefore(';').trim()
                    name.equals("Accept", true) && value in publicContentTypes -> value
                    else -> REDACTED
                }
            result.put(JSONObject().put("name", name).put("value", visible))
        }
        return result.toString()
    }

    /** Never consume streaming/one-shot bodies or copy an unbounded/non-JSON payload. */
    private fun requestBody(request: Request): String {
        val body = request.body ?: return "<none>"
        if (body.isOneShot() || body.isDuplex() || body.contentType()?.subtype != "json") {
            return OMITTED
        }
        if (body.contentLength() !in 0..MAX_BODY_BYTES) {
            return OMITTED
        }
        val buffer = Buffer()
        body.writeTo(buffer)
        if (buffer.size > MAX_BODY_BYTES) {
            return OMITTED
        }
        return json(buffer.readUtf8())
    }

    /** Peek a bounded copy so adapters still receive the complete original response body. */
    private fun responseBody(response: Response): String {
        if (response.body.contentType()?.subtype != "json") {
            return OMITTED
        }
        return response.peekBody(MAX_BODY_BYTES + 1).use { copy ->
            val bytes = copy.bytes()
            if (bytes.size > MAX_BODY_BYTES) {
                return@use OMITTED
            }
            json(bytes.toString(Charsets.UTF_8))
        }
    }

    /** Invalid JSON is omitted entirely; raw parsing errors can contain private body content. */
    private fun json(text: String): String {
        if (!hasBoundedNesting(text)) {
            return OMITTED
        }
        try {
            val value = JSONTokener(text).nextValue()
            require(value is JSONObject || value is JSONArray)
            return sanitize(value, 0).toString()
        } catch (_: Exception) {
            return OMITTED
        }
    }

    /** Bound parser recursion without interpreting braces or brackets inside JSON strings. */
    private fun hasBoundedNesting(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            when {
                escaped -> escaped = false
                inString && character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' || character == '[' -> depth++
                character == '}' || character == ']' -> depth--
            }
            if (depth > MAX_DEPTH) {
                return false
            }
        }
        return true
    }

    /** Preserve bounded JSON shape recursively, exposing only explicitly permitted public terms. */
    private fun sanitize(value: Any?, depth: Int, key: String = ""): Any {
        if (depth >= MAX_DEPTH) {
            return OMITTED
        }
        return when (value) {
            is JSONObject -> {
                val result = JSONObject()
                value.keys().asSequence().take(MAX_FIELDS).forEach { field ->
                    result.put(field, sanitize(value.get(field), depth + 1, field))
                }
                result
            }
            is JSONArray -> {
                val result = JSONArray()
                for (index in 0 until minOf(value.length(), MAX_FIELDS)) {
                    result.put(sanitize(value.get(index), depth + 1, key))
                }
                result
            }
            else -> publicValue(key, value)
        }
    }

    /** Unknown values default to redaction, including future credential and payment fields. */
    private fun publicValue(key: String, value: Any?): Any {
        if (key == "requestId" && value is String && isValidApiRequestId(value)) {
            return value
        }
        if (value is String && value in publicEnums[key].orEmpty()) {
            return value
        }
        if (key == "isSubmissionAvailable" && value is Boolean) {
            return value
        }
        if (key == "limit" && value is String && value.toIntOrNull() in 1..100) {
            return value
        }
        return REDACTED
    }

    /** Best-effort diagnostics must not fail a call or expose exception messages/body fragments. */
    private fun record(id: String, label: String, content: () -> String) {
        try {
            val safe = content().take(MAX_LOG_CHARS)
            safe.chunked(2800).forEachIndexed { index, chunk ->
                logger("[$id] $label [$index]: $chunk")
            }
        } catch (_: Exception) {
            // Keep logging/parsing failure separate from the actual HTTP outcome.
        }
    }

    /** Small fixed limits keep diagnostics bounded, including malformed or oversized responses. */
    private companion object {
        const val MAX_BODY_BYTES = 65536L
        const val MAX_LOG_CHARS = 12000
        const val MAX_FIELDS = 25
        const val MAX_DEPTH = 8
        const val REDACTED = "<redacted>"
        const val OMITTED = "<body omitted>"
        val publicContentTypes = setOf("application/json", "text/plain")
        val publicPaths =
            setOf(
                "",
                "api",
                "v1",
                "auth",
                "signup",
                "verify",
                "login",
                "password",
                "code",
                "me",
                "logout",
                "wallets",
                "payments",
                "quotes",
                "by-request-key",
                "reconcile",
                "documents",
                "service-catalog",
                "products",
                "bill-accounts",
                "validations",
                "account-validations",
            )
        val publicEnums =
            mapOf(
                "currency" to setOf("NGN"),
                "serviceType" to
                    setOf(
                        "airtime",
                        "data",
                        "electricity",
                        "tv",
                        "education",
                        "insurance",
                        "bank_transfer",
                        "merchant",
                    ),
                "networkCode" to setOf("mtn", "airtel", "glo", "9mobile", "smile", "spectranet"),
                "paymentStatus" to
                    setOf("awaiting_funds", "reserved", "pending", "succeeded", "failed"),
                "walletStatus" to setOf("active", "restricted", "closed"),
                "kind" to setOf("phone", "email"),
                "tokenType" to setOf("Bearer"),
                "code" to
                    setOf(
                        "INVALID_CREDENTIALS",
                        "UNAUTHORIZED",
                        "RATE_LIMITED",
                        "QUOTE_EXPIRED",
                        "INSUFFICIENT_FUNDS",
                        "WALLET_RESTRICTED",
                        "PAYMENT_CONFLICT",
                        "IDEMPOTENCY_CONFLICT",
                        "PRODUCT_UNAVAILABLE",
                        "INVALID_PAYMENT",
                        "PAYMENT_UNAVAILABLE",
                        "QUOTE_NOT_FOUND",
                        "PAYMENT_NOT_FOUND",
                    ),
            )
    }
}
