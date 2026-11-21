package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.wallet.domain.FundingContext
import com.kesofty.axiapay.wallet.domain.FundingFailure
import com.kesofty.axiapay.wallet.domain.FundingIssue
import com.kesofty.axiapay.wallet.domain.FundingRepository
import com.kesofty.axiapay.wallet.domain.WalletRepository

/**
 * Discovers the account's current server wallet and loads its funding options and request history.
 */
class LoadFundingContext(
    private val access: FundingSessionAccess,
    private val wallets: WalletRepository,
    private val funding: FundingRepository,
) {
    /** Reads one page for the verified account without creating a funding request. */
    suspend operator fun invoke(accountId: String, offset: Int = 0): FundingContext =
        access.withAccount(accountId, funding) { token ->
            if (wallets.apiIdentity != funding.apiIdentity)
                throw FundingFailure(FundingIssue.SignInRequired)
            val wallet = wallets.currentWallet(token, "NGN")
            funding.context(token, wallet.id, offset)
        }
}
