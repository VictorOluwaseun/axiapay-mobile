package com.kesofty.axiapay.data.auth

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.auth.*
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Calls only the configured API auth endpoints with bounded, cancellable, uncached requests.
 * Redirects, automatic retries and credential logging are disabled at composition. It owns JSON
 * mapping and safe HTTP failures, never session storage, payment state or authentication policy.
 */
class HttpAuthenticationRepository(
    private val http: ApiHttpClient,
    clock: Clock,
) : AuthenticationRepository {
    /**
     * Plain assembly for isolated transport tests; production shares one configured HTTP client.
     */
    constructor(
        baseUrl: String,
        allowLocalHttp: Boolean,
        client: OkHttpClient,
        io: CoroutineDispatcher,
        clock: Clock,
    ) : this(ApiHttpClient(baseUrl, allowLocalHttp, client, io), clock)

    private val codec = AuthenticationJsonCodec(clock)
    override val apiIdentity: String
        get() =
            try {
                http.apiIdentity
            } catch (failure: ApiRequestFailure) {
                throw mappedFailure(failure)
            }

    /** Encodes only contact and optional signup password for the purpose-specific code endpoint. */
    override suspend fun requestCode(
        contact: AccountContact,
        purpose: AuthenticationPurpose,
        password: String?,
    ): AuthenticationChallengeTerms {
        val payload = JSONObject().put("contact", codec.contact(contact))
        if (password != null) {
            require(purpose == AuthenticationPurpose.Signup)
            payload.put("password", password)
        }
        val path = if (purpose == AuthenticationPurpose.Signup) "signup" else "login/code"
        return decode { codec.challenge(requireNotNull(request(path, 202, payload))) }
    }

    /** Maps a successful password-login DTO to an opaque, redacted session value. */
    override suspend fun loginWithPassword(
        contact: AccountContact,
        password: String,
    ): AuthenticatedSession = decode {
        codec.session(
            requireNotNull(
                request(
                    "login/password",
                    200,
                    JSONObject().put("contact", codec.contact(contact)).put("password", password),
                ),
            ),
        )
    }

    /** Uses the original challenge purpose and maps generic 401 responses to code rejection. */
    override suspend fun verifyCode(
        challenge: AuthenticationChallenge,
        code: String,
    ): AuthenticatedSession {
        val path =
            if (challenge.purpose == AuthenticationPurpose.Signup) "signup/verify"
            else "login/code/verify"
        val success = if (challenge.purpose == AuthenticationPurpose.Signup) 201 else 200
        return try {
            decode {
                codec.session(
                    requireNotNull(
                        request(
                            path,
                            success,
                            JSONObject().put("challengeId", challenge.id).put("code", code),
                        ),
                    ),
                )
            }
        } catch (failure: AuthenticationFailure) {
            if (failure.issue != AccountAccessIssue.InvalidCredentials) {
                throw failure
            }
            throw AuthenticationFailure(
                AccountAccessIssue.CodeRejected,
                requestReference = failure.requestReference,
            )
        }
    }

    /** Reads the verified identity from the uncached bearer-protected /me endpoint. */
    override suspend fun currentAccount(accessToken: String): AccountIdentity = decode {
        codec.account(requireNotNull(request("me", 200, bearer = accessToken)))
    }

    /** Accepts revocation or an already-invalid session without reading a response body. */
    override suspend fun logout(accessToken: String) {
        try {
            request("logout", 204, JSONObject(), accessToken)
        } catch (failure: AuthenticationFailure) {
            if (failure.issue != AccountAccessIssue.InvalidCredentials) {
                throw failure
            }
        }
    }

    /**
     * Map shared transport failures to the authentication contract without exposing response data.
     */
    private fun mappedFailure(failure: ApiRequestFailure): AuthenticationFailure {
        val issue =
            when {
                failure.kind == ApiFailureKind.Configuration -> AccountAccessIssue.Configuration
                failure.kind == ApiFailureKind.Network -> AccountAccessIssue.Network
                failure.kind == ApiFailureKind.InvalidResponse -> AccountAccessIssue.InvalidResponse
                failure.status == 403 && failure.code == "AUTHENTICATION_METHOD_DISABLED" ->
                    AccountAccessIssue.MethodDisabled
                failure.status == 400 -> AccountAccessIssue.InvalidRequest
                failure.status == 401 -> AccountAccessIssue.InvalidCredentials
                failure.status == 429 -> AccountAccessIssue.RateLimited
                else -> AccountAccessIssue.Unavailable
            }
        return AuthenticationFailure(issue, failure.retryAfterSeconds, failure.requestReference)
    }

    /**
     * Keep auth path ownership here while delegating common bounded IO and credential-safe
     * transport.
     */
    private suspend fun request(
        path: String,
        expected: Int,
        payload: JSONObject? = null,
        bearer: String? = null,
    ): JSONObject? {
        try {
            return http.request("auth/$path", expected, payload, bearer)
        } catch (failure: ApiRequestFailure) {
            throw mappedFailure(failure)
        }
    }

    /** Retains approved classifications while rejecting malformed transport objects safely. */
    private suspend fun <T> decode(read: suspend () -> T): T {
        try {
            return read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AuthenticationFailure) {
            throw failure
        } catch (failure: Exception) {
            throw AuthenticationFailure(AccountAccessIssue.InvalidResponse)
        }
    }
}
