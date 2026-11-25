package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*

/** Recovers wallet purchases by their saved request keys. */
class WalletPurchases(
    private val sessionAccess: PurchaseSessionAccess,
    private val purchases: PurchaseRepository,
    private val requestStore: PurchaseRequestStore,
) {
    /**
     * Read only on restoration or status checks. A missing record is unconfirmed and retains its
     * key.
     */
    suspend fun recover(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { access ->
            requireSaved(access.owner, request)
            val payment = purchases.findByRequestKey(access.session.accessToken, request.requestKey)
            if (payment != null) {
                return@withAccount PurchaseProgress(request, payment)
            }
            val quote = recoverQuote(access, request)
            PurchaseProgress(request, null, quote, PurchaseIssue.NotFound)
        }

    /**
     * Explicitly recover accepted held progress. Awaiting funds must use the distinct resume
     * action.
     */
    suspend fun reconcile(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { access ->
            requireSaved(access.owner, request)
            val payment =
                purchases.findByRequestKey(access.session.accessToken, request.requestKey)
                    ?: return@withAccount PurchaseProgress(
                        request,
                        null,
                        recoverQuote(access, request),
                        PurchaseIssue.NotFound,
                    )
            val current = PurchaseProgress(request, payment)
            if (!payment.canReconcile) {
                return@withAccount current
            }
            sessionAccess.requireCurrent(access.session)
            PurchaseProgress(request, purchases.reconcile(access.session.accessToken, payment.id))
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
        progress.request.requireMatchingPayment(payment)
        requestStore.acknowledge(progress.request)
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

    /** Look up the exact journaled instruction before observing or reconciling its payment. */
    private suspend fun requireSaved(owner: PurchaseOwner, request: PurchaseRequest) {
        request.requireOwnedBy(owner)
        if (request !in requestStore.read(owner)) throw PurchaseFailure(PurchaseIssue.NotFound)
    }
}
