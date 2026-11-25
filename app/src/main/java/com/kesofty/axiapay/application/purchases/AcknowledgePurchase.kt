package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*

/** Marks an eligible final receipt as acknowledged in its original purchase journal. */
class AcknowledgePurchase(private val requests: PurchaseRequestStore) {
    /** Require an observed, matching payment before updating its saved reference. */
    suspend operator fun invoke(progress: PurchaseProgress) {
        val payment =
            progress.payment?.takeIf { it.canAcknowledge }
                ?: throw PurchaseFailure(PurchaseIssue.Unavailable)
        progress.request.requireMatchingPayment(payment)
        requests.acknowledge(progress.request)
    }
}
