package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.wallet.domain.*
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Binds purchase reads and payments to the account and API that issued the stored session. */
class PurchaseSessionAccess(
    private val authentication: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val wallets: WalletRepository,
    private val purchases: PurchaseRepository,
    private val clock: Clock,
) {
    /** Keep the captured credential inside the application workflow, never in UI or saved state. */
    internal data class VerifiedAccount(
        val owner: PurchaseOwner,
        val session: StoredAuthenticationSession,
        val identity: AccountIdentity,
    ) {
        /** Prevent accidental diagnostic interpolation of a bearer. */
        override fun toString() = "PurchaseAccess(redacted)"
    }

    /** Authorize a local display read and check the same login again before publishing it. */
    internal suspend fun <T> withCachedOwner(
        accountId: String,
        read: suspend (PurchaseOwner) -> T,
    ): T {
        val session = sessions.read() ?: throw PurchaseFailure(PurchaseIssue.SignInRequired)
        val identity = purchaseApiIdentity()
        if (
            session.accountId != accountId ||
                session.apiIdentity != identity ||
                !session.isUsableAt(clock.instant())
        ) {
            throw PurchaseFailure(PurchaseIssue.SessionChanged)
        }
        val result = read(PurchaseOwner(accountId, identity))
        requireCurrent(session)
        return result
    }

    /** Reject a mixed API configuration before using an account or reading its snapshots. */
    private fun purchaseApiIdentity(): String {
        val identity = purchases.apiIdentity
        if (identity != authentication.apiIdentity || identity != wallets.apiIdentity) {
            throw PurchaseFailure(PurchaseIssue.Configuration)
        }
        return identity
    }

    /**
     * Bind each operation to the captured protected session, including across account changes
     * during IO.
     */
    internal suspend fun <T> withAccount(
        accountId: String,
        operation: suspend (VerifiedAccount) -> T,
    ): T {
        var captured: StoredAuthenticationSession? = null
        try {
            val session = sessions.read() ?: throw PurchaseFailure(PurchaseIssue.SignInRequired)
            captured = session
            val identity = purchaseApiIdentity()
            if (session.apiIdentity != identity || !session.isUsableAt(clock.instant())) {
                sessions.clear(session)
                throw PurchaseFailure(PurchaseIssue.SignInRequired)
            }
            val account = authentication.currentAccount(session.accessToken)
            if (account.id != accountId) {
                throw PurchaseFailure(PurchaseIssue.SessionChanged)
            }
            val access = VerifiedAccount(PurchaseOwner(accountId, identity), session, account)
            requireCurrent(access.session)
            val result = operation(access)
            requireCurrent(access.session)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AuthenticationFailure) {
            val issue =
                when (failure.issue) {
                    AccountAccessIssue.Network -> PurchaseIssue.Network
                    AccountAccessIssue.Configuration -> PurchaseIssue.Configuration
                    AccountAccessIssue.SecureStorage -> PurchaseIssue.Storage
                    AccountAccessIssue.InvalidCredentials -> PurchaseIssue.SignInRequired
                    else -> PurchaseIssue.Unavailable
                }
            if (issue == PurchaseIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw PurchaseFailure(issue, failure)
        } catch (failure: WalletFailure) {
            val issue =
                when (failure.issue) {
                    WalletIssue.Missing -> PurchaseIssue.WalletMissing
                    WalletIssue.InvalidSession -> PurchaseIssue.SignInRequired
                    WalletIssue.Network -> PurchaseIssue.Network
                    WalletIssue.Configuration -> PurchaseIssue.Configuration
                    WalletIssue.InvalidResponse -> PurchaseIssue.InvalidResponse
                    WalletIssue.Unavailable -> PurchaseIssue.WalletUnavailable
                }
            if (issue == PurchaseIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw PurchaseFailure(issue, failure)
        } catch (failure: PurchaseFailure) {
            if (failure.issue == PurchaseIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw failure
        } catch (failure: Exception) {
            throw PurchaseFailure(PurchaseIssue.Unavailable, failure)
        }
    }

    /** A late response from a replaced/expired credential cannot enter another account's UI. */
    internal suspend fun requireCurrent(session: StoredAuthenticationSession) {
        if (!isCurrentSession(session)) throw PurchaseFailure(PurchaseIssue.SessionChanged)
    }

    /** Recheck the captured family immediately before updating a display snapshot. */
    internal suspend fun isCurrentSession(session: StoredAuthenticationSession): Boolean {
        currentCoroutineContext().ensureActive()
        val current = sessions.read()
        currentCoroutineContext().ensureActive()
        return session.belongsToSameSession(current) && current?.isUsableAt(clock.instant()) == true
    }
}
