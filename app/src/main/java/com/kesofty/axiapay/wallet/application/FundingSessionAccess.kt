package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.domain.auth.AuthenticationRepository
import com.kesofty.axiapay.domain.auth.AuthenticationSessionStore
import com.kesofty.axiapay.domain.auth.StoredAuthenticationSession
import com.kesofty.axiapay.wallet.domain.FundingFailure
import com.kesofty.axiapay.wallet.domain.FundingIssue
import com.kesofty.axiapay.wallet.domain.FundingRepository
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Verifies account and API ownership before handing a session token to a funding operation. */
class FundingSessionAccess(
    private val authentication: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val clock: Clock,
) {
    /** Capture one API/account/session family; late responses cannot enter a replacement login. */
    suspend fun <T> withAccount(
        accountId: String,
        funding: FundingRepository,
        operation: suspend (String) -> T,
    ): T {
        try {
            val session = sessions.read() ?: throw FundingFailure(FundingIssue.SignInRequired)
            if (
                session.apiIdentity != funding.apiIdentity ||
                    funding.apiIdentity != authentication.apiIdentity ||
                    !session.isUsableAt(clock.instant())
            ) {
                throw FundingFailure(FundingIssue.SignInRequired)
            }
            if (authentication.currentAccount(session.accessToken).id != accountId) {
                throw FundingFailure(FundingIssue.SessionChanged)
            }
            requireSession(session)
            val result = operation(session.accessToken)
            requireSession(session)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FundingFailure) {
            throw failure
        } catch (_: Exception) {
            throw FundingFailure(FundingIssue.Unavailable)
        }
    }

    /**
     * Preserve session renewal within a family while rejecting logout, account changes and
     * cancellation.
     */
    private suspend fun requireSession(captured: StoredAuthenticationSession) {
        currentCoroutineContext().ensureActive()
        val current = sessions.read()
        if (
            !captured.belongsToSameSession(current) || current?.isUsableAt(clock.instant()) != true
        ) {
            throw FundingFailure(FundingIssue.SessionChanged)
        }
    }
}
