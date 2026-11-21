package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.wallet.domain.FundingRepository
import com.kesofty.axiapay.wallet.domain.FundingRequest

/** Reads the server's saved funding progress for history and restoration. */
class GetFundingRequest(
    private val access: FundingSessionAccess,
    private val funding: FundingRepository,
) {
    /** Retrieves the selected request for the captured account. */
    suspend operator fun invoke(accountId: String, requestId: String): FundingRequest =
        access.withAccount(accountId, funding) { token -> funding.get(token, requestId) }
}
