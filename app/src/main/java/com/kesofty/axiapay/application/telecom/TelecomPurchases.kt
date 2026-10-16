package com.kesofty.axiapay.application.telecom

import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.telecom.*
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
class TelecomPurchases(
    private val authentication: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val wallets: WalletRepository,
    private val telecom: TelecomRepository,
    private val requestStore: TelecomRequestStore,
    private val clock: Clock,
    private val newRequestId: () -> String,
) {
    /** Keep the captured credential inside the application workflow, never in UI or saved state. */
    private data class Access(val owner: TelecomOwner, val session: StoredAuthenticationSession) {
        /** Prevent accidental diagnostic interpolation of a bearer. */
        override fun toString() = "TelecomAccess(redacted)"
    }

    /**
     * Load an authenticated wallet, current catalogue and this account's saved request references.
     */
    suspend fun load(
        accountId: String,
        service: TelecomService = TelecomService.Airtime,
    ): TelecomContext =
        withAccess(accountId) { access ->
            val requests =
                Collections.unmodifiableList(
                    requestStore.read(access.owner).filter { it.service == service },
                )
            try {
                val wallet = wallets.currentWallet(access.session.accessToken, "NGN")
                val products =
                    telecom.products(access.session.accessToken, wallet.currency, service)
                TelecomContext(
                    access.owner,
                    wallet,
                    Collections.unmodifiableList(products.toList()),
                    requests,
                )
            } catch (failure: WalletFailure) {
                if (requests.isEmpty() || failure.issue == WalletIssue.InvalidSession) {
                    throw failure
                }
                TelecomContext(
                    access.owner,
                    null,
                    emptyList(),
                    requests,
                    TelecomIssue.WalletUnavailable,
                )
            } catch (failure: TelecomFailure) {
                if (
                    requests.isEmpty() ||
                        failure.issue in
                            setOf(TelecomIssue.SignInRequired, TelecomIssue.SessionChanged)
                ) {
                    throw failure
                }
                TelecomContext(access.owner, null, emptyList(), requests, failure.issue)
            }
        }

    /**
     * Request server pricing for an already prepared input; bind the result to the verified wallet
     * and recipient.
     */
    suspend fun review(accountId: String, input: TelecomQuoteInput): TelecomReview =
        withAccess(accountId) { access ->
            val wallet = wallets.currentWallet(access.session.accessToken, "NGN")
            val quote = telecom.quote(access.session.accessToken, wallet.id, input)
            val matches =
                quote.service == input.service &&
                    quote.walletId == wallet.id &&
                    quote.productId == input.productId &&
                    quote.recipientPhoneNumber == input.recipientPhoneNumber &&
                    quote.currency == wallet.currency &&
                    (input.amountMinorUnits == null ||
                        input.amountMinorUnits == quote.principalMinorUnits)
            if (!matches) {
                throw TelecomFailure(TelecomIssue.InvalidResponse)
            }
            TelecomReview(
                TelecomRequest(
                    access.owner,
                    newRequestId(),
                    quote.id,
                    clock.instant(),
                    service = input.service,
                ),
                quote,
            )
        }

    /**
     * Persist the original identity before the sole explicitly confirmed POST; storage failure
     * sends nothing.
     */
    suspend fun submit(accountId: String, review: TelecomReview): TelecomProgress =
        withAccess(accountId) { access ->
            requireOwner(access, review.request)
            if (
                review.request.quoteId != review.quote.id ||
                    review.request.service != review.quote.service
            ) {
                throw TelecomFailure(TelecomIssue.InvalidResponse)
            }
            if (!review.quote.isSubmissionAvailable) {
                throw TelecomFailure(TelecomIssue.ProductUnavailable)
            }
            if (review.quote.expiresAt <= clock.instant()) {
                throw TelecomFailure(TelecomIssue.QuoteExpired)
            }
            requestStore.save(review.request)
            assertCurrentSession(access)
            transmit(access, review.request, review.quote)
        }

    /**
     * Read only on restoration or status checks. A missing record is unconfirmed and retains its
     * key.
     */
    suspend fun recover(accountId: String, request: TelecomRequest): TelecomProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            val payment = telecom.findByRequestKey(access.session.accessToken, request.requestKey)
            if (payment != null) {
                return@withAccess observed(request, payment)
            }
            val quote = recoverQuote(access, request)
            TelecomProgress(request, null, quote, TelecomIssue.NotFound)
        }

    /**
     * Explicitly retry only the original saved quote/key; never requote or manufacture a new
     * payment after uncertainty.
     */
    suspend fun retrySubmission(accountId: String, request: TelecomRequest): TelecomProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            assertCurrentSession(access)
            transmit(access, request, null)
        }

    /**
     * Explicitly recover accepted held progress. Awaiting funds must use the distinct resume
     * action.
     */
    suspend fun reconcile(accountId: String, request: TelecomRequest): TelecomProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            val payment =
                telecom.findByRequestKey(access.session.accessToken, request.requestKey)
                    ?: return@withAccess TelecomProgress(
                        request,
                        null,
                        recoverQuote(access, request),
                        TelecomIssue.NotFound,
                    )
            val current = observed(request, payment)
            if (payment.isFinal || payment.status == TelecomPaymentStatus.AwaitingFunds) {
                return@withAccess current
            }
            assertCurrentSession(access)
            observed(request, telecom.reconcile(access.session.accessToken, payment.id))
        }

    /**
     * Acknowledge only observed final progress; closing an unfinished sheet never forgets a
     * request.
     */
    suspend fun acknowledge(progress: TelecomProgress) {
        val payment = progress.payment ?: throw TelecomFailure(TelecomIssue.Unavailable)
        if (!payment.isFinal) {
            throw TelecomFailure(TelecomIssue.Unavailable)
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
        request: TelecomRequest,
        reviewed: TelecomQuote?,
    ): TelecomProgress {
        try {
            val payment = telecom.submit(access.session.accessToken, request)
            val result = observed(request, payment)
            if (reviewed != null && !reviewed.hasSameTerms(payment.quote)) {
                throw TelecomFailure(TelecomIssue.InvalidResponse)
            }
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TelecomFailure) {
            if (failure.issue in setOf(TelecomIssue.SignInRequired, TelecomIssue.SessionChanged)) {
                throw failure
            }
            return TelecomProgress(request, null, reviewed, failure.issue)
        }
    }

    /**
     * A recovered quote helps explain uncertainty but is never substituted for an observed payment.
     */
    private suspend fun recoverQuote(access: Access, request: TelecomRequest): TelecomQuote? {
        try {
            val quote = telecom.getQuote(access.session.accessToken, request.quoteId)
            if (quote.id != request.quoteId || quote.service != request.service) {
                throw TelecomFailure(TelecomIssue.InvalidResponse)
            }
            return quote
        } catch (failure: TelecomFailure) {
            if (failure.issue == TelecomIssue.NotFound) {
                return null
            }
            throw failure
        }
    }

    /** Correlate every submission/recovery response to the persisted logical identity. */
    private fun observed(request: TelecomRequest, payment: TelecomPayment): TelecomProgress {
        if (
            payment.requestKey != request.requestKey ||
                payment.quote.id != request.quoteId ||
                payment.quote.service != request.service
        ) {
            throw TelecomFailure(TelecomIssue.InvalidResponse)
        }
        return TelecomProgress(request, payment, payment.quote)
    }

    /** Check record scope without interpreting a cached wallet as authorization. */
    private fun requireOwner(access: Access, request: TelecomRequest) {
        if (request.owner != access.owner) {
            throw TelecomFailure(TelecomIssue.SessionChanged)
        }
    }

    /** Resumes require the durable original instruction; no caller may swap its quote/key. */
    private suspend fun requireSaved(access: Access, request: TelecomRequest) {
        requireOwner(access, request)
        if (requestStore.read(access.owner).none { it == request }) {
            throw TelecomFailure(TelecomIssue.NotFound)
        }
    }

    /**
     * Bind each operation to the captured protected session, including across account changes
     * during IO.
     */
    private suspend fun <T> withAccess(accountId: String, operation: suspend (Access) -> T): T {
        var captured: StoredAuthenticationSession? = null
        try {
            val session = sessions.read() ?: throw TelecomFailure(TelecomIssue.SignInRequired)
            captured = session
            val identity = telecom.apiIdentity
            val sameApi = identity == authentication.apiIdentity && identity == wallets.apiIdentity
            if (!sameApi) {
                throw TelecomFailure(TelecomIssue.Configuration)
            }
            if (session.apiIdentity != identity || session.expiresAt <= clock.instant()) {
                sessions.clear(session)
                throw TelecomFailure(TelecomIssue.SignInRequired)
            }
            val account = authentication.currentAccount(session.accessToken)
            if (account.id != accountId) {
                throw TelecomFailure(TelecomIssue.SessionChanged)
            }
            val access = Access(TelecomOwner(accountId, identity), session)
            assertCurrentSession(access)
            val result = operation(access)
            assertCurrentSession(access)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AuthenticationFailure) {
            val issue =
                when (failure.issue) {
                    AccountAccessIssue.Network -> TelecomIssue.Network
                    AccountAccessIssue.Configuration -> TelecomIssue.Configuration
                    AccountAccessIssue.SecureStorage -> TelecomIssue.Storage
                    AccountAccessIssue.InvalidCredentials -> TelecomIssue.SignInRequired
                    else -> TelecomIssue.Unavailable
                }
            if (issue == TelecomIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw TelecomFailure(issue, failure)
        } catch (failure: WalletFailure) {
            val issue =
                when (failure.issue) {
                    WalletIssue.Missing -> TelecomIssue.WalletMissing
                    WalletIssue.InvalidSession -> TelecomIssue.SignInRequired
                    WalletIssue.Network -> TelecomIssue.Network
                    WalletIssue.Configuration -> TelecomIssue.Configuration
                    WalletIssue.InvalidResponse -> TelecomIssue.InvalidResponse
                    WalletIssue.Unavailable -> TelecomIssue.WalletUnavailable
                }
            if (issue == TelecomIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw TelecomFailure(issue, failure)
        } catch (failure: TelecomFailure) {
            if (failure.issue == TelecomIssue.SignInRequired && captured != null) {
                sessions.clear(captured)
            }
            throw failure
        } catch (failure: Exception) {
            throw TelecomFailure(TelecomIssue.Unavailable, failure)
        }
    }

    /** A late response from a replaced/expired credential cannot enter another account's UI. */
    private suspend fun assertCurrentSession(access: Access) {
        currentCoroutineContext().ensureActive()
        val current = sessions.read()
        currentCoroutineContext().ensureActive()
        if (current != access.session || access.session.expiresAt <= clock.instant()) {
            throw TelecomFailure(TelecomIssue.SessionChanged)
        }
    }
}
