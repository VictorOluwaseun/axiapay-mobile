package com.kesofty.axiapay.application.auth

import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.wallet.*
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coordinates explicit backend auth operations and protected session persistence. It owns client
 * validation/recovery only; the API authenticates identities and authorizes protected requests.
 * Cancellation and ambiguous redemption outcomes never trigger automatic retries or demo fallback.
 */
class ServerAccountAccess(
    private val repository: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val clock: Clock,
    private val wallets: WalletRepository,
) : AccountAccess {
    override val mode = AccountAccessMode.Server

    /** Coordinates one validated contact/method submission without retaining its password. */
    override suspend fun begin(input: AccountAccessInput): AccountAccessResult {
        val contact = normalizedAccountContact(input)
        accountInputIssue(input, contact)?.let {
            return AccountAccessResult.Rejected(it)
        }
        if (!input.isCreatingAccount && input.method == AuthMethod.Password) {
            return persist(repository.loginWithPassword(contact, input.password))
        }
        val purpose =
            if (input.isCreatingAccount) AuthenticationPurpose.Signup
            else AuthenticationPurpose.Login
        val password =
            input.password.takeIf { input.isCreatingAccount && input.method == AuthMethod.Password }
        return requestChallenge(contact, purpose, password)
    }

    /** Checks local proof shape/expiry before the server redeems its original challenge. */
    override suspend fun verify(
        challenge: AuthenticationChallenge?,
        code: String,
    ): AccountAccessResult {
        if (challenge == null) {
            return AccountAccessResult.Rejected(AccountAccessIssue.CodeExpired)
        }
        if (!Regex("[0-9]{6}").matches(code)) {
            return AccountAccessResult.Rejected(AccountAccessIssue.Code)
        }
        if (!clock.instant().isBefore(challenge.expiresAt)) {
            return AccountAccessResult.Rejected(AccountAccessIssue.CodeExpired)
        }
        return persist(repository.verifyCode(challenge, code))
    }

    /** Honors server timing and prevents password signup from silently becoming passwordless. */
    override suspend fun resend(
        challenge: AuthenticationChallenge?,
        input: AccountAccessInput,
    ): AccountAccessResult {
        if (challenge == null) {
            return AccountAccessResult.Rejected(AccountAccessIssue.CodeExpired)
        }
        val remaining = secondsUntil(challenge.resendAt)
        if (remaining > 0) {
            return AccountAccessResult.Rejected(AccountAccessIssue.RateLimited, remaining)
        }
        if (challenge.purpose == AuthenticationPurpose.Signup && challenge.usesPassword) {
            return AccountAccessResult.Rejected(AccountAccessIssue.SignupPasswordRequired)
        }
        return requestChallenge(challenge.contact, challenge.purpose, null)
    }

    /**
     * Rechecks scoped stored credentials; only authoritative invalidation removes valid-shaped
     * access.
     */
    override suspend fun restore(): AccountAccessResult {
        val stored = sessions.read() ?: return AccountAccessResult.SignedOut
        if (
            stored.apiIdentity != repository.apiIdentity ||
                !clock.instant().isBefore(stored.expiresAt)
        ) {
            sessions.clear(stored)
            return AccountAccessResult.SignedOut
        }
        return try {
            val account = repository.currentAccount(stored.accessToken)
            currentCoroutineContext().ensureActive()
            signedInWithWallet(account, stored)
        } catch (failure: AuthenticationFailure) {
            if (failure.issue != AccountAccessIssue.InvalidCredentials) {
                throw failure
            }
            currentCoroutineContext().ensureActive()
            sessions.clear(stored)
            AccountAccessResult.SignedOut
        }
    }

    /**
     * Revokes this API session before clearing local access; connectivity failure remains
     * retryable.
     */
    override suspend fun signOut(): AccountAccessResult {
        val stored = sessions.read() ?: return AccountAccessResult.SignedOut
        if (stored.apiIdentity == repository.apiIdentity) {
            repository.logout(stored.accessToken)
        }
        sessions.clear(stored)
        return AccountAccessResult.SignedOut
    }

    /**
     * Persists only a still-owned result, so a cancellation-resistant adapter cannot save late
     * credentials.
     */
    private suspend fun persist(session: AuthenticatedSession): AccountAccessResult {
        currentCoroutineContext().ensureActive()
        val stored =
            StoredAuthenticationSession(
                session.accessToken,
                session.expiresAt,
                repository.apiIdentity,
            )
        sessions.write(stored)
        currentCoroutineContext().ensureActive()
        return signedInWithWallet(session.account, stored)
    }

    /**
     * Fetch only after persisted authentication; failures remain wallet-specific and never redeem
     * credentials again.
     */
    private suspend fun signedInWithWallet(
        account: AccountIdentity,
        stored: StoredAuthenticationSession,
    ): AccountAccessResult {
        if (sessions.read() != stored) {
            return AccountAccessResult.SignedOut
        }
        val wallet = loadWallet(stored)
        currentCoroutineContext().ensureActive()
        if (sessions.read() != stored) {
            return AccountAccessResult.SignedOut
        }
        if (wallet == WalletLookupResult.Failed(WalletIssue.InvalidSession)) {
            sessions.clear(stored)
            return AccountAccessResult.SignedOut
        }
        return AccountAccessResult.SignedIn(account, wallet)
    }

    /**
     * Bind the GET to the captured API/session; unavailable or missing display data never becomes a
     * fake balance.
     */
    private suspend fun loadWallet(stored: StoredAuthenticationSession): WalletLookupResult {
        try {
            if (wallets.apiIdentity != stored.apiIdentity) {
                return WalletLookupResult.Failed(WalletIssue.Configuration)
            }
            return WalletLookupResult.Loaded(wallets.currentWallet(stored.accessToken, "NGN"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: WalletFailure) {
            return WalletLookupResult.Failed(failure.issue)
        } catch (failure: Exception) {
            return WalletLookupResult.Failed(WalletIssue.Unavailable)
        }
    }

    /**
     * Uses fresh server timing and identity on every request; no password survives this operation.
     */
    private suspend fun requestChallenge(
        contact: AccountContact,
        purpose: AuthenticationPurpose,
        password: String?,
    ): AccountAccessResult {
        val startedAt = clock.instant()
        val terms = repository.requestCode(contact, purpose, password)
        return AccountAccessResult.CodeRequired(
            AuthenticationChallenge(
                terms.id,
                purpose,
                contact,
                password != null,
                startedAt.plusSeconds(terms.expiresInSeconds),
                clock.instant().plusSeconds(terms.resendAfterSeconds),
            ),
        )
    }

    /** Rounds up for UI cooldowns; these local timers are never authorization evidence. */
    private fun secondsUntil(deadline: java.time.Instant): Long =
        ((java.time.Duration.between(clock.instant(), deadline).toMillis() + 999) / 1000)
            .coerceAtLeast(0)
}
