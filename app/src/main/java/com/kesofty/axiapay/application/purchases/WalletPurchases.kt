package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.purchases.*
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
class WalletPurchases(
    private val authentication: AuthenticationRepository,
    private val sessions: AuthenticationSessionStore,
    private val wallets: WalletRepository,
    private val purchases: PurchaseRepository,
    private val requestStore: PurchaseRequestStore,
    private val clock: Clock,
    private val newRequestId: () -> String,
) {
    /** Keep the captured credential inside the application workflow, never in UI or saved state. */
    private data class Access(val owner: PurchaseOwner, val session: StoredAuthenticationSession) {
        /** Prevent accidental diagnostic interpolation of a bearer. */
        override fun toString() = "PurchaseAccess(redacted)"
    }

    /**
     * Load an authenticated wallet, current catalogue and this account's saved request references.
     */
    suspend fun load(
        accountId: String,
        service: PurchaseService = PurchaseService.Airtime,
    ): PurchaseContext =
        withAccess(accountId) { access ->
            val requests =
                Collections.unmodifiableList(
                    requestStore.read(access.owner).filter { it.service == service },
                )
            try {
                val wallet = wallets.currentWallet(access.session.accessToken, "NGN")
                val products =
                    purchases.products(access.session.accessToken, wallet.currency, service)
                PurchaseContext(
                    access.owner,
                    wallet,
                    Collections.unmodifiableList(products.toList()),
                    requests,
                )
            } catch (failure: WalletFailure) {
                if (requests.isEmpty() || failure.issue == WalletIssue.InvalidSession) {
                    throw failure
                }
                PurchaseContext(
                    access.owner,
                    null,
                    emptyList(),
                    requests,
                    PurchaseIssue.WalletUnavailable,
                )
            } catch (failure: PurchaseFailure) {
                if (
                    requests.isEmpty() ||
                        failure.issue in
                            setOf(PurchaseIssue.SignInRequired, PurchaseIssue.SessionChanged)
                ) {
                    throw failure
                }
                PurchaseContext(access.owner, null, emptyList(), requests, failure.issue)
            }
        }

    /** Verify selected API product/mode and bind the returned meter to the captured owner. */
    suspend fun verifyMeter(
        accountId: String,
        product: PurchaseProduct,
        accountNumber: String,
    ): VerifiedMeter =
        withAccess(accountId) { access ->
            val result =
                purchases.verifyMeter(access.session.accessToken, product.id, accountNumber)
            if (
                product.service != PurchaseService.Electricity ||
                    result.productId != product.id ||
                    result.accountNumber != accountNumber ||
                    result.billerCode != product.providerCode ||
                    result.meterType != product.meterType
            ) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            if (result.expiresAt <= clock.instant()) {
                throw PurchaseFailure(PurchaseIssue.MeterVerificationExpired)
            }
            VerifiedMeter(access.owner, result)
        }

    /**
     * Request server pricing for an already prepared input; bind the result to the verified wallet
     * and recipient.
     */
    suspend fun review(accountId: String, input: PurchaseQuoteInput): PurchaseReview =
        withAccess(accountId) { access ->
            requireCurrentMeter(access, input)
            val wallet = wallets.currentWallet(access.session.accessToken, "NGN")
            val quote = purchases.quote(access.session.accessToken, wallet.id, input)
            val matches =
                quote.service == input.service &&
                    quote.walletId == wallet.id &&
                    quote.productId == input.productId &&
                    quote.recipientIdentifier == input.recipientIdentifier &&
                    quote.currency == wallet.currency &&
                    quote.meterValidation == input.verifiedMeter?.validation &&
                    (input.service != PurchaseService.Electricity ||
                        quote.contactPhoneNumber == input.contactPhoneNumber) &&
                    (input.amountMinorUnits == null ||
                        input.amountMinorUnits == quote.principalMinorUnits)
            if (!matches) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            PurchaseReview(
                PurchaseRequest(
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
    suspend fun submit(accountId: String, review: PurchaseReview): PurchaseProgress =
        withAccess(accountId) { access ->
            requireOwner(access, review.request)
            if (
                review.request.quoteId != review.quote.id ||
                    review.request.service != review.quote.service
            ) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            if (!review.quote.isSubmissionAvailable) {
                throw PurchaseFailure(PurchaseIssue.ProductUnavailable)
            }
            if (review.quote.expiresAt <= clock.instant()) {
                throw PurchaseFailure(PurchaseIssue.QuoteExpired)
            }
            requestStore.save(review.request)
            assertCurrentSession(access)
            transmit(access, review.request, review.quote)
        }

    /**
     * Read only on restoration or status checks. A missing record is unconfirmed and retains its
     * key.
     */
    suspend fun recover(accountId: String, request: PurchaseRequest): PurchaseProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            val payment = purchases.findByRequestKey(access.session.accessToken, request.requestKey)
            if (payment != null) {
                return@withAccess observed(request, payment)
            }
            val quote = recoverQuote(access, request)
            PurchaseProgress(request, null, quote, PurchaseIssue.NotFound)
        }

    /**
     * Explicitly retry only the original saved quote/key; never requote or manufacture a new
     * payment after uncertainty.
     */
    suspend fun retrySubmission(accountId: String, request: PurchaseRequest): PurchaseProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            assertCurrentSession(access)
            transmit(access, request, null)
        }

    /**
     * Explicitly recover accepted held progress. Awaiting funds must use the distinct resume
     * action.
     */
    suspend fun reconcile(accountId: String, request: PurchaseRequest): PurchaseProgress =
        withAccess(accountId) { access ->
            requireSaved(access, request)
            val payment =
                purchases.findByRequestKey(access.session.accessToken, request.requestKey)
                    ?: return@withAccess PurchaseProgress(
                        request,
                        null,
                        recoverQuote(access, request),
                        PurchaseIssue.NotFound,
                    )
            val current = observed(request, payment)
            if (!payment.canReconcile) {
                return@withAccess current
            }
            assertCurrentSession(access)
            observed(request, purchases.reconcile(access.session.accessToken, payment.id))
        }

    /**
     * Acknowledge only observed final progress; closing an unfinished sheet never forgets a
     * request.
     */
    suspend fun acknowledge(progress: PurchaseProgress) {
        val payment = progress.payment ?: throw PurchaseFailure(PurchaseIssue.Unavailable)
        if (!payment.canAcknowledge) {
            throw PurchaseFailure(PurchaseIssue.Unavailable)
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
        request: PurchaseRequest,
        reviewed: PurchaseQuote?,
    ): PurchaseProgress {
        try {
            val payment = purchases.submit(access.session.accessToken, request)
            val result = observed(request, payment)
            if (reviewed != null && !reviewed.hasSameTerms(payment.quote)) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PurchaseFailure) {
            if (
                failure.issue in setOf(PurchaseIssue.SignInRequired, PurchaseIssue.SessionChanged)
            ) {
                throw failure
            }
            return PurchaseProgress(request, null, reviewed, failure.issue)
        }
    }

    /**
     * A recovered quote helps explain uncertainty but is never substituted for an observed payment.
     */
    private suspend fun recoverQuote(access: Access, request: PurchaseRequest): PurchaseQuote? {
        try {
            val quote = purchases.getQuote(access.session.accessToken, request.quoteId)
            if (quote.id != request.quoteId || quote.service != request.service) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            return quote
        } catch (failure: PurchaseFailure) {
            if (failure.issue == PurchaseIssue.NotFound) {
                return null
            }
            throw failure
        }
    }

    /** Correlate every submission/recovery response to the persisted logical identity. */
    private fun observed(request: PurchaseRequest, payment: PurchasePayment): PurchaseProgress {
        if (
            payment.requestKey != request.requestKey ||
                payment.quote.id != request.quoteId ||
                payment.quote.service != request.service
        ) {
            throw PurchaseFailure(PurchaseIssue.InvalidResponse)
        }
        return PurchaseProgress(request, payment, payment.quote)
    }

    /** A validation capability belongs to one owner/API and must be fresh at quote creation. */
    private fun requireCurrentMeter(access: Access, input: PurchaseQuoteInput) {
        if (input.service != PurchaseService.Electricity) {
            return
        }
        val verified =
            input.verifiedMeter ?: throw PurchaseFailure(PurchaseIssue.MeterVerificationRequired)
        if (verified.owner != access.owner) {
            throw PurchaseFailure(PurchaseIssue.SessionChanged)
        }
        if (verified.validation.expiresAt <= clock.instant()) {
            throw PurchaseFailure(PurchaseIssue.MeterVerificationExpired)
        }
    }

    /** Check record scope without interpreting a cached wallet as authorization. */
    private fun requireOwner(access: Access, request: PurchaseRequest) {
        if (request.owner != access.owner) {
            throw PurchaseFailure(PurchaseIssue.SessionChanged)
        }
    }

    /** Resumes require the durable original instruction; no caller may swap its quote/key. */
    private suspend fun requireSaved(access: Access, request: PurchaseRequest) {
        requireOwner(access, request)
        if (requestStore.read(access.owner).none { it == request }) {
            throw PurchaseFailure(PurchaseIssue.NotFound)
        }
    }

    /**
     * Bind each operation to the captured protected session, including across account changes
     * during IO.
     */
    private suspend fun <T> withAccess(accountId: String, operation: suspend (Access) -> T): T {
        var captured: StoredAuthenticationSession? = null
        try {
            val session = sessions.read() ?: throw PurchaseFailure(PurchaseIssue.SignInRequired)
            captured = session
            val identity = purchases.apiIdentity
            val sameApi = identity == authentication.apiIdentity && identity == wallets.apiIdentity
            if (!sameApi) {
                throw PurchaseFailure(PurchaseIssue.Configuration)
            }
            if (session.apiIdentity != identity || !session.isUsableAt(clock.instant())) {
                sessions.clear(session)
                throw PurchaseFailure(PurchaseIssue.SignInRequired)
            }
            val account = authentication.currentAccount(session.accessToken)
            if (account.id != accountId) {
                throw PurchaseFailure(PurchaseIssue.SessionChanged)
            }
            val access = Access(PurchaseOwner(accountId, identity), session)
            assertCurrentSession(access)
            val result = operation(access)
            assertCurrentSession(access)
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
    private suspend fun assertCurrentSession(access: Access) {
        currentCoroutineContext().ensureActive()
        val current = sessions.read()
        currentCoroutineContext().ensureActive()
        if (
            !access.session.belongsToSameSession(current) ||
                current?.isUsableAt(clock.instant()) != true
        ) {
            throw PurchaseFailure(PurchaseIssue.SessionChanged)
        }
    }
}
