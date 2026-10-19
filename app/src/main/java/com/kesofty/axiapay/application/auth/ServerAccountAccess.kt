package com.kesofty.axiapay.application.auth

import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.dashboard.DashboardRepository
import com.kesofty.axiapay.domain.wallet.*
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coordinates explicit backend auth operations and protected session persistence. It owns client
 * validation/recovery and committed dashboard display; the API authenticates identities and
 * authorizes protected requests. Cancellation and ambiguous redemption outcomes never trigger
 * automatic retries or demo fallback.
 */
class ServerAccountAccess(
    private val repository: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val clock: Clock,
    private val wallets: WalletRepository,
    private val dashboard: DashboardRepository,
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
     * Restore committed display data only for the exact, still-owned and locally unexpired session.
     */
    override suspend fun cached(): AccountAccessResult.SignedIn? {
        val stored = sessions.read() ?: return null
        if (
            stored.apiIdentity != repository.apiIdentity ||
                !clock.instant().isBefore(stored.expiresAt)
        ) {
            return null
        }
        val accountId = stored.accountId ?: return null
        val snapshot = dashboard.read(stored.apiIdentity, accountId) ?: return null
        if (sessions.read() != stored) {
            return null
        }
        return AccountAccessResult.SignedIn(
            snapshot.account,
            snapshot.wallet?.let { WalletLookupResult.Loaded(it) }
                ?: WalletLookupResult.Failed(WalletIssue.Unavailable),
        )
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
            clearSession(stored)
            return AccountAccessResult.SignedOut
        }
        return try {
            val account = repository.currentAccount(stored.accessToken)
            currentCoroutineContext().ensureActive()
            if (stored.accountId != null && account.id != stored.accountId) {
                throw AuthenticationFailure(AccountAccessIssue.InvalidResponse)
            }
            val bound = stored.copy(accountId = account.id)
            if (sessions.read() != stored) {
                return AccountAccessResult.SignedOut
            }
            if (bound != stored) {
                sessions.write(bound)
            }
            signedInWithWallet(account, bound)
        } catch (failure: AuthenticationFailure) {
            if (failure.issue != AccountAccessIssue.InvalidCredentials) {
                throw failure
            }
            currentCoroutineContext().ensureActive()
            clearSession(stored)
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
        clearSession(stored)
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
                session.account.id,
            )
        sessions.write(stored)
        currentCoroutineContext().ensureActive()
        return signedInWithWallet(session.account, stored)
    }

    /**
     * Commit identity and wallet responses before publishing their stored display snapshot.
     * Transient wallet failures retain this account's earlier record without redeeming credentials.
     */
    private suspend fun signedInWithWallet(
        account: AccountIdentity,
        stored: StoredAuthenticationSession,
    ): AccountAccessResult {
        if (sessions.read() != stored) {
            return AccountAccessResult.SignedOut
        }
        dashboard.saveAccount(stored.apiIdentity, account)
        val wallet = loadWallet(stored)
        currentCoroutineContext().ensureActive()
        if (sessions.read() != stored) {
            return AccountAccessResult.SignedOut
        }
        if (wallet == WalletLookupResult.Failed(WalletIssue.InvalidSession)) {
            clearSession(stored)
            return AccountAccessResult.SignedOut
        }
        if (wallet == WalletLookupResult.Failed(WalletIssue.Missing)) {
            dashboard.clearWallet(stored.apiIdentity, account.id, "NGN")
        }
        if (wallet is WalletLookupResult.Loaded) {
            dashboard.saveWallet(stored.apiIdentity, account.id, wallet.wallet)
        }
        if (sessions.read() != stored) {
            return AccountAccessResult.SignedOut
        }
        val saved =
            dashboard.read(stored.apiIdentity, account.id)
                ?: throw AuthenticationFailure(AccountAccessIssue.SecureStorage)
        val displayedWallet = saved.wallet?.let { WalletLookupResult.Loaded(it) } ?: wallet
        return AccountAccessResult.SignedIn(saved.account, displayedWallet)
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
     * Clear only the captured session; a newer login keeps both its credential and its dashboard
     * records.
     */
    private suspend fun clearSession(stored: StoredAuthenticationSession) {
        sessions.clear(stored)
        if (sessions.read() != null) {
            return
        }
        stored.accountId?.let { dashboard.clear(stored.apiIdentity, it) }
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
