package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*
import java.time.Clock

/** Sends an explicitly confirmed purchase under its original, durable request key. */
class SubmitPurchase(
    private val sessionAccess: PurchaseSessionAccess,
    private val purchases: PurchaseRepository,
    private val requestStore: PurchaseRequestStore,
    private val clock: Clock,
) {
    /** Verify the reviewed terms before committing the request and sending it to the server. */
    suspend operator fun invoke(accountId: String, review: PurchaseReview): PurchaseProgress =
        sessionAccess.withAccount(accountId) { access ->
            val (request, quote) = review
            request.requireOwnedBy(access.owner)
            if (request.quoteId != quote.id || request.service != quote.service) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            if (!quote.isSubmissionAvailable)
                throw PurchaseFailure(PurchaseIssue.ProductUnavailable)
            if (quote.expiresAt <= clock.instant())
                throw PurchaseFailure(PurchaseIssue.QuoteExpired)
            requestStore.save(request)
            send(access, request, quote)
        }

    /** A user retry uses the exact journaled request, including its original quote and key. */
    suspend fun retry(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { access ->
            request.requireOwnedBy(access.owner)
            requestStore.requireDurablySaved(request)
            send(access, request, null)
        }

    /**
     * Recheck the session before sending. An uncertain response keeps the saved request
     * recoverable.
     */
    private suspend fun send(
        access: PurchaseSessionAccess.VerifiedAccount,
        request: PurchaseRequest,
        reviewed: PurchaseQuote?,
    ): PurchaseProgress {
        sessionAccess.requireCurrent(access.session)
        try {
            val payment = purchases.submit(access.session.accessToken, request)
            if (reviewed != null && !reviewed.hasSameTerms(payment.quote)) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            return PurchaseProgress(request, payment)
        } catch (failure: PurchaseFailure) {
            if (
                failure.issue == PurchaseIssue.SignInRequired ||
                    failure.issue == PurchaseIssue.SessionChanged
            ) {
                throw failure
            }
            return PurchaseProgress(request, null, reviewed, failure.issue)
        }
    }
}
