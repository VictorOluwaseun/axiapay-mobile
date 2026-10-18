package com.kesofty.axiapay.data.config

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.TextCheckpoint
import com.kesofty.axiapay.domain.config.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Owns local-first reads and one atomic, API-scoped cache record. Cached display reads never wait
 * for network revalidation. Online failures remain explicit and preserve the committed snapshot;
 * client defaults are never persisted as remotely fetched or versioned settings.
 */
class RemoteConfigRepositoryImpl(
    private val http: ApiHttpClient,
    private val checkpoint: TextCheckpoint,
) : RemoteConfigRepository {
    private val cacheMutex = Mutex()
    private val refreshMutex = Mutex()
    private var cached: RemoteConfiguration? = null

    /** Read a validated committed snapshot without waiting for a network refresh in progress. */
    override suspend fun readCached(): RemoteConfiguration? = cacheMutex.withLock {
        val identity =
            try {
                http.apiIdentity
            } catch (_: ApiRequestFailure) {
                throw RemoteConfigFailure(RemoteConfigIssue.Configuration)
            }
        loadCached(identity)
    }

    /** Serialize online refreshes; publish a replacement only after successful persistence. */
    override suspend fun refresh(): RemoteConfiguration = refreshMutex.withLock {
        try {
            refreshLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RemoteConfigFailure) {
            throw failure
        } catch (failure: ApiRequestFailure) {
            val issue =
                when (failure.kind) {
                    ApiFailureKind.Network -> RemoteConfigIssue.Network
                    ApiFailureKind.Configuration -> RemoteConfigIssue.Configuration
                    ApiFailureKind.InvalidResponse -> RemoteConfigIssue.InvalidResponse
                    ApiFailureKind.Http -> RemoteConfigIssue.Unavailable
                }
            throw RemoteConfigFailure(issue)
        } catch (_: Exception) {
            throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        }
    }

    /** A 304 is usable only with the exact cached representation and matching quoted ETag. */
    private suspend fun refreshLocked(): RemoteConfiguration {
        val identity = http.apiIdentity
        val previous = cacheMutex.withLock { loadCached(identity) }
        val response = http.conditionalGet("config", previous?.let { etag(it) })
        if (response.status == 304 && previous != null && response.etag == etag(previous)) {
            return previous
        }
        if (response.status == 304) {
            throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        }
        val body = requireNotNull(response.body)
        val configuration = decode(body)
        if (response.etag != etag(configuration)) {
            throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        }
        val record = JSONObject().put("apiIdentity", identity).put("configuration", body).toString()
        cacheMutex.withLock {
            try {
                checkpoint.write(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw RemoteConfigFailure(RemoteConfigIssue.Storage)
            }
            cached = configuration
        }
        return configuration
    }

    /** Keep memory hits fast; retry local misses so transient disk failures can recover offline. */
    private suspend fun loadCached(identity: String): RemoteConfiguration? {
        if (cached == null) {
            cached = readCache(identity)
        }
        return cached
    }

    /** Corrupt, missing or differently scoped records cannot seed display or HTTP validators. */
    private suspend fun readCache(identity: String): RemoteConfiguration? {
        try {
            val content = checkpoint.read() ?: return null
            if (content.length > 65536) {
                return null
            }
            val record = JSONObject(content)
            if (record.opt("apiIdentity") != identity) {
                return null
            }
            return decode(record.getJSONObject("configuration"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    /** Validate untrusted HTTP/disk values once; JSON string booleans never count as booleans. */
    private fun decode(body: JSONObject): RemoteConfiguration {
        val version =
            body.opt("version") as? String
                ?: throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        if (
            version.length !in 1..128 ||
                !version.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
        ) {
            throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        }
        val authentication = body.getJSONObject("authentication")
        val signup = authentication.getJSONObject("signup")
        val login = authentication.getJSONObject("login")
        val credentialFields =
            listOf(signup, login).flatMap { policy ->
                listOf(policy.has("passwordEnabled"), policy.has("otpEnabled"))
            }
        if (credentialFields.any { it } && credentialFields.any { !it }) {
            throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        }
        return RemoteConfiguration(
            version,
            methods(signup),
            methods(login),
            features(body),
        )
    }

    /**
     * Older auth-only snapshots retain login policy but cannot enable payment features. Partial new
     * policies are invalid.
     */
    private fun features(body: JSONObject): PaymentFeatures {
        if (!body.has("features")) return PaymentFeatures.Disabled
        val values = body.getJSONObject("features")
        fun enabled(feature: PaymentFeature): Boolean =
            values.getJSONObject(feature.configurationKey).opt("enabled") as? Boolean
                ?: throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        return PaymentFeatures(
            airtime = enabled(PaymentFeature.Airtime),
            data = enabled(PaymentFeature.Data),
            electricity = enabled(PaymentFeature.Electricity),
            tv = enabled(PaymentFeature.Tv),
            education = enabled(PaymentFeature.Education),
            insurance = enabled(PaymentFeature.Insurance),
            bankTransfer = enabled(PaymentFeature.BankTransfer),
            merchant = enabled(PaymentFeature.Merchant),
            airtimeConversion = enabled(PaymentFeature.AirtimeConversion),
        )
    }

    /**
     * Legacy contact-only snapshots retain both credential choices. Partial new policies and
     * malformed booleans reject the whole replacement; saved disabled values never become defaults.
     */
    private fun methods(body: JSONObject): AuthenticationMethods {
        val emailEnabled =
            body.opt("emailEnabled") as? Boolean
                ?: throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        val phoneEnabled =
            body.opt("phoneEnabled") as? Boolean
                ?: throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse)
        if (!body.has("passwordEnabled") && !body.has("otpEnabled")) {
            return AuthenticationMethods(emailEnabled, phoneEnabled)
        }
        return AuthenticationMethods(
            emailEnabled,
            phoneEnabled,
            body.opt("passwordEnabled") as? Boolean
                ?: throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse),
            body.opt("otpEnabled") as? Boolean
                ?: throw RemoteConfigFailure(RemoteConfigIssue.InvalidResponse),
        )
    }

    /**
     * Keep HTTP validators paired with their validated body rather than independent preferences.
     */
    private fun etag(configuration: RemoteConfiguration) = "\"${configuration.version}\""
}
