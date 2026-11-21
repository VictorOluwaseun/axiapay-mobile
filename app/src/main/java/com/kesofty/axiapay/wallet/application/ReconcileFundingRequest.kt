package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.wallet.domain.FundingRepository
import com.kesofty.axiapay.wallet.domain.FundingRequest

/** Asks the server to reconcile the status of an existing funding request. */
class ReconcileFundingRequest(
    private val access: FundingSessionAccess,
    private val funding: FundingRepository,
) {
    /** Checks the selected request after an explicit customer action. */
    suspend operator fun invoke(accountId: String, requestId: String): FundingRequest =
        access.withAccount(accountId, funding) { token -> funding.reconcile(token, requestId) }
}
