package com.kesofty.axiapay.data.auth

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.auth.*
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Coordinates one-use refresh proof with protected atomic storage. A private raw transport avoids
 * recursive authentication; all resource adapters share this one application-scoped coordinator.
 */
class AuthenticationSessionRepositoryImpl(
    private val http: ApiHttpClient,
    private val sessions: AuthenticationSessionStore,
    private val clock: Clock,
) : SessionTokenProvider {
    private val renewal = Mutex()
    private val codec = AuthenticationJsonCodec(clock)
    private var pendingReplacement:
        Pair<StoredAuthenticationSession, StoredAuthenticationSession>? =
        null
    private val retiredAccessTokens = linkedMapOf<String, String>()

    /**
     * A queued request can use a replacement only when it belongs to its original API and family.
     */
    override suspend fun accessToken(
        apiIdentity: String,
        expectedToken: String,
        forceRenewal: Boolean,
    ): String = renewal.withLock {
        commitPendingReplacement()
        val current =
            sessions.read() ?: throw AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        if (apiIdentity != http.apiIdentity || current.apiIdentity != apiIdentity) {
            throw AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        }
        val currentProof = current.refreshCredentials
        val sameToken = current.accessToken == expectedToken
        val retiredFamily = retiredAccessTokens[expectedToken]
        if (!sameToken && (currentProof == null || retiredFamily != currentProof.sessionId)) {
            throw AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        }
        if (!current.isUsableAt(clock.instant())) {
            sessions.clear(current)
            throw AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        }
        val nearExpiry = !clock.instant().plusSeconds(30).isBefore(current.expiresAt)
        if (!nearExpiry && (!forceRenewal || !sameToken)) {
            return@withLock current.accessToken
        }
        if (currentProof == null && !forceRenewal && clock.instant().isBefore(current.expiresAt)) {
            return@withLock current.accessToken
        }
        if (currentProof == null) {
            sessions.clear(current)
            throw AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        }
        val response =
            try {
                http.request(
                    "auth/refresh",
                    200,
                    JSONObject().put("refreshToken", currentProof.token),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ApiRequestFailure) {
                if (failure.status == 401) {
                    sessions.clear(current)
                }
                throw mappedFailure(failure)
            }
        val renewed =
            try {
                codec.session(requireNotNull(response))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw AuthenticationFailure(AccountAccessIssue.InvalidResponse)
            }
        val nextProof = renewed.refreshCredentials
        if (
            nextProof == null ||
                nextProof.sessionId != currentProof.sessionId ||
                (current.accountId != null && renewed.account.id != current.accountId) ||
                renewed.accessToken == current.accessToken ||
                nextProof.token == currentProof.token
        ) {
            throw AuthenticationFailure(AccountAccessIssue.InvalidResponse)
        }
        val deadline = minOf(currentProof.expiresAt, nextProof.expiresAt)
        val replacement =
            current.copy(
                accessToken = renewed.accessToken,
                expiresAt = minOf(renewed.expiresAt, deadline),
                accountId = renewed.account.id,
                refreshCredentials = nextProof.copy(expiresAt = deadline),
            )
        pendingReplacement = current to replacement
        currentCoroutineContext().ensureActive()
        commitPendingReplacement()
        if (sessions.read() != replacement) {
            throw AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        }
        replacement.accessToken
    }

    /**
     * Retry a received pair's local commit, preserving logout/new-login races without sending old
     * proof again.
     */
    private suspend fun commitPendingReplacement() {
        val pending = pendingReplacement ?: return
        val current = sessions.read()
        if (current != pending.first && current != pending.second) {
            pendingReplacement = null
            return
        }
        if (current == pending.first && !sessions.replace(pending.first, pending.second)) {
            pendingReplacement = null
            return
        }
        retiredAccessTokens[pending.first.accessToken] =
            requireNotNull(pending.second.refreshCredentials).sessionId
        while (retiredAccessTokens.size > 8) {
            retiredAccessTokens.remove(retiredAccessTokens.keys.first())
        }
        pendingReplacement = null
    }

    /** Clear an authoritative rejection without removing a newer login or rotated credential. */
    override suspend fun rejected(apiIdentity: String, accessToken: String) = renewal.withLock {
        val current = sessions.read()
        if (current?.apiIdentity == apiIdentity && current.accessToken == accessToken) {
            sessions.clear(current)
        }
    }

    /** Preserve temporary failures; rejected proof alone removes the captured protected record. */
    private fun mappedFailure(failure: ApiRequestFailure): AuthenticationFailure {
        val issue =
            when {
                failure.kind == ApiFailureKind.Configuration -> AccountAccessIssue.Configuration
                failure.kind == ApiFailureKind.Network -> AccountAccessIssue.Network
                failure.kind == ApiFailureKind.InvalidResponse -> AccountAccessIssue.InvalidResponse
                failure.status == 401 -> AccountAccessIssue.InvalidCredentials
                failure.status == 429 -> AccountAccessIssue.RateLimited
                else -> AccountAccessIssue.Unavailable
            }
        return AuthenticationFailure(issue, failure.retryAfterSeconds, failure.requestReference)
    }
}
