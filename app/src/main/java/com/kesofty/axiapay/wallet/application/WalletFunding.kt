package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.wallet.domain.*
import java.math.BigDecimal
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coordinate session ownership, wallet discovery and funding requests without deciding payment
 * outcomes locally.
 */
class WalletFunding(
    private val authentication: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val wallets: WalletRepository,
    private val funding: FundingRepository,
    private val clock: Clock,
) {
    /** Restore server-owned requests without submitting or confirming a transfer. */
    suspend fun load(accountId: String, offset: Int = 0): FundingContext =
        withAccess(accountId) { token ->
            val wallet = wallets.currentWallet(token, "NGN")
            funding.context(token, wallet.id, offset)
        }

    /**
     * Validate client entry once and construct exact, immutable request terms for explicit
     * submission.
     */
    fun prepare(option: FundingOption, amount: String, email: String, key: String): FundingInput {
        if (!Regex("(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,2})?").matches(amount)) {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        val minor = BigDecimal(amount).movePointRight(2).toBigIntegerExact()
        if (minor < option.minimum || minor > option.maximum || option.currency != "NGN") {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        if (!option.bankTransfer && email.isBlank()) {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        return FundingInput(option.id, minor, email.trim().takeIf { !option.bankTransfer }, key)
    }

    /** Recheck the current wallet and captured session before sending the same logical intent. */
    suspend fun create(accountId: String, walletId: String, input: FundingInput): FundingRequest =
        withAccess(accountId) { token -> funding.create(token, walletId, input) }

    /** Read only after recreation or history selection. */
    suspend fun get(accountId: String, requestId: String): FundingRequest =
        withAccess(accountId) { token -> funding.get(token, requestId) }

    /**
     * Explicit status check can recover an already approved ledger posting; it cannot approve
     * customer claims.
     */
    suspend fun refresh(accountId: String, requestId: String): FundingRequest =
        withAccess(accountId) { token -> funding.reconcile(token, requestId) }

    /** Save bounded matching evidence and wait for independent review. */
    suspend fun submitReference(
        accountId: String,
        requestId: String,
        reference: String,
    ): FundingRequest {
        val normalized = reference.trim()
        if (normalized.isEmpty() || normalized.length > 200 || normalized.contains('\u0000')) {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        return withAccess(accountId) { token ->
            funding.submitReference(token, requestId, normalized)
        }
    }

    /** Capture one API/account/session family; late responses cannot enter a replacement login. */
    private suspend fun <T> withAccess(accountId: String, operation: suspend (String) -> T): T {
        try {
            val session = sessions.read() ?: throw FundingFailure(FundingIssue.SignInRequired)
            if (
                session.apiIdentity != funding.apiIdentity ||
                    funding.apiIdentity != wallets.apiIdentity ||
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
