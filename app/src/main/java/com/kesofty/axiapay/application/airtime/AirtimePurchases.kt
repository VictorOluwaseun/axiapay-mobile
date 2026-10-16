package com.kesofty.axiapay.application.airtime

import com.kesofty.axiapay.domain.airtime.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.wallet.*
import java.time.Clock
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coordinate protected session identity, owned wallet discovery, reviewed API purchases and a
 * durable recovery journal. The server owns pricing, spending, dispatch and settlement. No
 * lifecycle event submits, resumes or reconciles a payment automatically.
 */
class AirtimePurchases(
    private val authentication: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val wallets: WalletRepository,
    private val airtime: AirtimeRepository,
    private val requestStore: AirtimeRequestStore,
    private val clock: Clock,
    private val newRequestId: () -> String,
) {
    /** Keep the captured credential inside the application workflow, never in UI or saved state. */
    private data class Access(val owner: AirtimeOwner, val session: StoredAuthenticationSession) {
        /** Prevent accidental diagnostic interpolation of a bearer. */
        override fun toString() = "AirtimeAccess(redacted)"
    }

    /**
     * Load an authenticated wallet, current catalogue and this account's saved request references.
     */
    suspend fun load(accountId: String): AirtimeContext =
        withAccess(accountId) { access ->
            val requests = Collections.unmodifiableList(requestStore.read(access.owner).toList())
            try {
                val wallet = wallets.currentWallet(access.session.accessToken, "NGN")
                val products = airtime.products(access.session.accessToken, wallet.currency)
                AirtimeContext(
                    access.owner,
                    wallet,
                    Collections.unmodifiableList(products.toList()),
                    requests,
                )
            } catch (failure: WalletFailure) {
                if (requests.isEmpty() || failure.issue == WalletIssue.InvalidSession) {
                    throw failure
                }
                AirtimeContext(
                    access.owner,
                    null,
                    emptyList(),
                    requests,
                    AirtimeIssue.WalletUnavailable,
                )
            } catch (failure: AirtimeFailure) {
                if (
                    requests.isEmpty() ||
                        failure.issue in
                            setOf(AirtimeIssue.SignInRequired, AirtimeIssue.SessionChanged)
                ) {
                    throw failure
                }
                AirtimeContext(access.owner, null, emptyList(), requests, failure.issue)
            }
        }

    /**
     * Request server pricing for an already prepared input; bind the result to the verified wallet
     * and recipient.
     */
    suspend fun review(accountId: String, input: AirtimeQuoteInput): AirtimeReview =
        withAccess(accountId) { access ->
            val wallet = wallets.currentWallet(access.session.accessToken, "NGN")
            val quote = airtime.quote(access.session.accessToken, wallet.id, input)
            val matches =
                quote.walletId == wallet.id &&
                    quote.productId == input.productId &&
                    quote.recipientPhoneNumber == input.recipientPhoneNumber &&
                    quote.currency == wallet.currency &&
                    (input.amountMinorUnits == null ||
                        input.amountMinorUnits == quote.principalMinorUnits)
            if (!matches) {
                throw AirtimeFailure(AirtimeIssue.InvalidResponse)
            }
            AirtimeReview(
                AirtimeRequest(access.owner, newRequestId(), quote.id, clock.instant()),
                quote,
            )
        }

    /**
     * Persist the original identity before the sole explicitly confirmed POST; storage failure
     * sends nothing.
     */
    suspend fun submit(accountId: String, review: AirtimeReview): AirtimeProgress =
        withAccess(accountId) { access ->
            requireOwner(access, review.request)
            if (!review.quote.isSubmissionAvailable) {
                throw AirtimeFailure(AirtimeIssue.ProductUnavailable)
            }
            if (review.quote.expiresAt <= clock.instant()) {
                throw AirtimeFailure(AirtimeIssue.QuoteExpired)
            }
            requestStore.save(review.request)
            assertCurrentSession(access)
            transmit(access, review.request, review.quote)
        }

    /**
     * Read only on restoration or status checks. A missing record is unconfirmed and retains its
     * key.
     */
    suspend fun recover(accountId: String, request: AirtimeRequest): AirtimeProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            val payment = airtime.findByRequestKey(access.session.accessToken, request.requestKey)
            if (payment != null) {
                return@withAccess observed(request, payment)
            }
            val quote = recoverQuote(access, request)
            AirtimeProgress(request, null, quote, AirtimeIssue.NotFound)
        }

    /**
     * Explicitly retry only the original saved quote/key; never requote or manufacture a new
     * payment after uncertainty.
     */
    suspend fun retrySubmission(accountId: String, request: AirtimeRequest): AirtimeProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            assertCurrentSession(access)
            transmit(access, request, null)
        }

    /**
     * Explicitly recover accepted held progress. Awaiting funds must use the distinct resume
     * action.
     */
    suspend fun reconcile(accountId: String, request: AirtimeRequest): AirtimeProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            val payment =
                airtime.findByRequestKey(access.session.accessToken, request.requestKey)
                    ?: return@withAccess AirtimeProgress(
                        request,
                        null,
                        recoverQuote(access, request),
                        AirtimeIssue.NotFound,
                    )
            val current = observed(request, payment)
            if (payment.isFinal || payment.status == AirtimePaymentStatus.AwaitingFunds) {
                return@withAccess current
            }
            assertCurrentSession(access)
            observed(request, airtime.reconcile(access.session.accessToken, payment.id))
        }

    /**
     * Acknowledge only observed final progress; closing an unfinished sheet never forgets a
     * request.
     */
    suspend fun acknowledge(progress: AirtimeProgress) {
        val payment = progress.payment ?: throw AirtimeFailure(AirtimeIssue.Unavailable)
        if (!payment.isFinal) {
            throw AirtimeFailure(AirtimeIssue.Unavailable)
        }
        observed(progress.request, payment)
        requestStore.acknowledge(progress.request)
    }

    /**
     * Keep uncertain responses recoverable; only authenticated, correlated server progress becomes
     * a receipt.
     */
    private suspend fun transmit(
        access: Access,
        request: AirtimeRequest,
        reviewed: AirtimeQuote?,
    ): AirtimeProgress {
        try {
            val payment = airtime.submit(access.session.accessToken, request)
            val result = observed(request, payment)
            if (reviewed != null && !reviewed.hasSameTerms(payment.quote)) {
                throw AirtimeFailure(AirtimeIssue.InvalidResponse)
            }
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AirtimeFailure) {
            if (failure.issue in setOf(AirtimeIssue.SignInRequired, AirtimeIssue.SessionChanged)) {
                throw failure
            }
            return AirtimeProgress(request, null, reviewed, failure.issue)
        }
    }

    /**
     * A recovered quote helps explain uncertainty but is never substituted for an observed payment.
     */
    private suspend fun recoverQuote(access: Access, request: AirtimeRequest): AirtimeQuote? {
        try {
            return airtime.getQuote(access.session.accessToken, request.quoteId)
        } catch (failure: AirtimeFailure) {
            if (failure.issue == AirtimeIssue.NotFound) {
                return null
            }
            throw failure
        }
    }

    /** Correlate every submission/recovery response to the persisted logical identity. */
    private fun observed(request: AirtimeRequest, payment: AirtimePayment): AirtimeProgress {
        if (payment.requestKey != request.requestKey || payment.quote.id != request.quoteId) {
            throw AirtimeFailure(AirtimeIssue.InvalidResponse)
        }
        return AirtimeProgress(request, payment, payment.quote)
    }

    /** Check record scope without interpreting a cached wallet as authorization. */
    private fun requireOwner(access: Access, request: AirtimeRequest) {
        if (request.owner != access.owner) {
            throw AirtimeFailure(AirtimeIssue.SessionChanged)
        }
    }

    /** Resumes require the durable original instruction; no caller may swap its quote/key. */
    private suspend fun requireSaved(access: Access, request: AirtimeRequest) {
        requireOwner(access, request)
        if (requestStore.read(access.owner).none { it == request }) {
            throw AirtimeFailure(AirtimeIssue.NotFound)
        }
    }

    /**
     * Bind each operation to the captured protected session, including across account changes
     * during IO.
     */
    private suspend fun <T> withAccess(accountId: String, operation: suspend (Access) -> T): T {
        var captured: StoredAuthenticationSession? = null
        try {
            val session = sessions.read() ?: throw AirtimeFailure(AirtimeIssue.SignInRequired)
            captured = session
            val identity = airtime.apiIdentity
            val sameApi = identity == authentication.apiIdentity && identity == wallets.apiIdentity
            if (!sameApi) {
                throw AirtimeFailure(AirtimeIssue.Configuration)
            }
            if (session.apiIdentity != identity || session.expiresAt <= clock.instant()) {
                sessions.clear(session)
                throw AirtimeFailure(AirtimeIssue.SignInRequired)
            }
            val account = authentication.currentAccount(session.accessToken)
            if (account.id != accountId) {
                throw AirtimeFailure(AirtimeIssue.SessionChanged)
            }
            val access = Access(AirtimeOwner(accountId, identity), session)
            assertCurrentSession(access)
            val result = operation(access)
            assertCurrentSession(access)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AuthenticationFailure) {
            val issue =
                when (failure.issue) {
                    AccountAccessIssue.Network -> AirtimeIssue.Network
                    AccountAccessIssue.Configuration -> AirtimeIssue.Configuration
                    AccountAccessIssue.SecureStorage -> AirtimeIssue.Storage
                    AccountAccessIssue.InvalidCredentials -> AirtimeIssue.SignInRequired
                    else -> AirtimeIssue.Unavailable
                }
            if (issue == AirtimeIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw AirtimeFailure(issue, failure)
        } catch (failure: WalletFailure) {
            val issue =
                when (failure.issue) {
                    WalletIssue.Missing -> AirtimeIssue.WalletMissing
                    WalletIssue.InvalidSession -> AirtimeIssue.SignInRequired
                    WalletIssue.Network -> AirtimeIssue.Network
                    WalletIssue.Configuration -> AirtimeIssue.Configuration
                    WalletIssue.InvalidResponse -> AirtimeIssue.InvalidResponse
                    WalletIssue.Unavailable -> AirtimeIssue.WalletUnavailable
                }
            if (issue == AirtimeIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw AirtimeFailure(issue, failure)
        } catch (failure: AirtimeFailure) {
            if (failure.issue == AirtimeIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw failure
        } catch (failure: Exception) {
            throw AirtimeFailure(AirtimeIssue.Unavailable, failure)
        }
    }

    /** A late response from a replaced/expired credential cannot enter another account's UI. */
    private suspend fun assertCurrentSession(access: Access) {
        currentCoroutineContext().ensureActive()
        val current = sessions.read()
        currentCoroutineContext().ensureActive()
        if (current != access.session || access.session.expiresAt <= clock.instant()) {
            throw AirtimeFailure(AirtimeIssue.SessionChanged)
        }
    }
}
