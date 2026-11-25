package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*

/** Reads the outcome of a saved purchase after restoration or an explicit status check. */
class RecoverPurchase(
    private val sessionAccess: PurchaseSessionAccess,
    private val purchases: PurchaseRepository,
    private val requests: PurchaseRequestStore,
) {
    /** Look up the original request key under its verified account and API. */
    suspend operator fun invoke(accountId: String, request: PurchaseRequest): PurchaseProgress =
        sessionAccess.withAccount(accountId) { account ->
            requests.requireSaved(account.owner, request)
            purchases.readProgress(account.session.accessToken, request)
        }
}
