package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.wallet.domain.FundingFailure
import com.kesofty.axiapay.wallet.domain.FundingIssue
import com.kesofty.axiapay.wallet.domain.FundingRepository
import com.kesofty.axiapay.wallet.domain.FundingRequest

/** Attaches a customer reference so the server can match a deposit. */
class SubmitFundingReference(
    private val access: FundingSessionAccess,
    private val funding: FundingRepository,
) {
    /** Save bounded matching evidence and wait for independent review. */
    suspend operator fun invoke(
        accountId: String,
        requestId: String,
        reference: String,
    ): FundingRequest {
        val normalized = reference.trim()
        if (normalized.isEmpty() || normalized.length > 200 || normalized.contains('\u0000')) {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        return access.withAccount(accountId, funding) { token ->
            funding.submitReference(token, requestId, normalized)
        }
    }
}
