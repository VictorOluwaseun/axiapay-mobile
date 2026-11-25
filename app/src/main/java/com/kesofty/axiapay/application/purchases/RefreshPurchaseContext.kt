package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.dashboard.DashboardRepository
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.wallet.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Refreshes the wallet and service catalogue used to display the purchase form. */
class RefreshPurchaseContext(
    private val access: PurchaseSessionAccess,
    private val wallets: WalletRepository,
    private val dashboard: DashboardRepository,
    private val catalogues: PurchaseCatalogueRepository,
    private val contexts: PurchaseContextReader,
) {
    /** Verify online ownership and preserve the prior committed display on a transient failure. */
    suspend operator fun invoke(
        accountId: String,
        service: PurchaseService = PurchaseService.Airtime,
    ): PurchaseContext =
        access.withAccount(accountId) { account ->
            dashboard.saveAccount(account.owner.apiIdentity, account.identity)
            try {
                refresh(account, service)
            } catch (failure: PurchaseFailure) {
                if (
                    failure.issue == PurchaseIssue.SignInRequired ||
                        failure.issue == PurchaseIssue.SessionChanged
                )
                    throw failure
                val wallet = wallets.cachedWallet(accountId, "NGN")
                contexts.read(account.owner, service, wallet, issue = failure.issue)
            }
        }

    /** Fetch both display sources concurrently and publish the committed terms for this session. */
    private suspend fun refresh(
        account: PurchaseSessionAccess.VerifiedAccount,
        service: PurchaseService,
    ): PurchaseContext = coroutineScope {
        val owner = account.owner
        val session = account.session
        val catalogue = async { catalogues.refresh(owner, session.accessToken, "NGN", service) }
        val balance =
            wallets.refreshDisplayWallet(owner.accountId, session.accessToken, "NGN") {
                access.isCurrentSession(session)
            }
        val issue =
            when (balance.issue) {
                null -> null
                WalletIssue.InvalidSession -> {
                    access.requireCurrent(session)
                    throw WalletFailure(WalletIssue.InvalidSession)
                }
                WalletIssue.Missing -> PurchaseIssue.WalletMissing
                else -> PurchaseIssue.WalletUnavailable
            }
        val wallet = (balance as? WalletLookupResult.Loaded)?.wallet
        if (issue == null) catalogue.await() else catalogue.cancel()
        contexts.read(owner, service, wallet, isCached = issue != null, issue = issue)
    }
}
