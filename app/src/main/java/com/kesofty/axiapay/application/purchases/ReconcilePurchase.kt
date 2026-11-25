package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*

/** Asks the server to reconcile an existing payment when its observed state permits it. */
class ReconcilePurchase(
    private val sessionAccess: PurchaseSessionAccess,
    private val purchases: PurchaseRepository,
    private val requests: PurchaseRequestStore,
) {
    /** Recheck the session before reconciling the payment identified by the saved request. */
    suspend operator fun invoke(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { account ->
            requests.requireSaved(account.owner, request)
            val token = account.session.accessToken
            val current = purchases.readProgress(token, request)
            val payment = current.payment?.takeIf { it.canReconcile } ?: return@withAccount current
            sessionAccess.requireCurrent(account.session)
            PurchaseProgress(request, purchases.reconcile(token, payment.id))
        }
}
