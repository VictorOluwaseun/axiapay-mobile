package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.wallet.domain.WalletRepository
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Verifies meters, requests quotes and keeps confirmed purchases recoverable by their original key.
 */
class WalletPurchases(
    private val sessionAccess: PurchaseSessionAccess,
    private val wallets: WalletRepository,
    private val purchases: PurchaseRepository,
    private val requestStore: PurchaseRequestStore,
    private val clock: Clock,
    private val newRequestId: () -> String,
) {
    /** Verify selected API product/mode and bind the returned meter to the captured owner. */
    suspend fun verifyMeter(
        accountId: String,
        product: PurchaseProduct,
        accountNumber: String,
    ): VerifiedMeter =
        sessionAccess.withAccount(accountId) { access ->
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
        sessionAccess.withAccount(accountId) { access ->
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
        sessionAccess.withAccount(accountId) { access ->
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
            sessionAccess.requireCurrent(access.session)
            transmit(access, review.request, review.quote)
        }

    /**
     * Read only on restoration or status checks. A missing record is unconfirmed and retains its
     * key.
     */
    suspend fun recover(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { access ->
            requireSaved(access, request)
            val payment = purchases.findByRequestKey(access.session.accessToken, request.requestKey)
            if (payment != null) {
                return@withAccount observed(request, payment)
            }
            val quote = recoverQuote(access, request)
            PurchaseProgress(request, null, quote, PurchaseIssue.NotFound)
        }

    /**
     * Explicitly retry only the original saved quote/key; never requote or manufacture a new
     * payment after uncertainty.
     */
    suspend fun retrySubmission(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { access ->
            requireSaved(access, request)
            sessionAccess.requireCurrent(access.session)
            transmit(access, request, null)
        }

    /**
     * Explicitly recover accepted held progress. Awaiting funds must use the distinct resume
     * action.
     */
    suspend fun reconcile(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { access ->
            requireSaved(access, request)
            val payment =
                purchases.findByRequestKey(access.session.accessToken, request.requestKey)
                    ?: return@withAccount PurchaseProgress(
                        request,
                        null,
                        recoverQuote(access, request),
                        PurchaseIssue.NotFound,
                    )
            val current = observed(request, payment)
            if (!payment.canReconcile) {
                return@withAccount current
            }
            sessionAccess.requireCurrent(access.session)
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
        access: PurchaseSessionAccess.VerifiedAccount,
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
    private suspend fun recoverQuote(
        access: PurchaseSessionAccess.VerifiedAccount,
        request: PurchaseRequest,
    ): PurchaseQuote? {
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
    private fun requireCurrentMeter(
        access: PurchaseSessionAccess.VerifiedAccount,
        input: PurchaseQuoteInput,
    ) {
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
    private fun requireOwner(
        access: PurchaseSessionAccess.VerifiedAccount,
        request: PurchaseRequest,
    ) {
        if (request.owner != access.owner) {
            throw PurchaseFailure(PurchaseIssue.SessionChanged)
        }
    }

    /** Resumes require the durable original instruction; no caller may swap its quote/key. */
    private suspend fun requireSaved(
        access: PurchaseSessionAccess.VerifiedAccount,
        request: PurchaseRequest,
    ) {
        requireOwner(access, request)
        if (requestStore.read(access.owner).none { it == request }) {
            throw PurchaseFailure(PurchaseIssue.NotFound)
        }
    }
}
