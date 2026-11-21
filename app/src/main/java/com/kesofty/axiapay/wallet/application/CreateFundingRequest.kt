package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.wallet.domain.FundingInput
import com.kesofty.axiapay.wallet.domain.FundingRepository
import com.kesofty.axiapay.wallet.domain.FundingRequest

/** Submits the prepared terms under their original request key. */
class CreateFundingRequest(
    private val access: FundingSessionAccess,
    private val funding: FundingRepository,
) {
    /** Creates a request for the captured account and wallet. */
    suspend operator fun invoke(
        accountId: String,
        walletId: String,
        input: FundingInput,
    ): FundingRequest =
        access.withAccount(accountId, funding) { token -> funding.create(token, walletId, input) }
}
