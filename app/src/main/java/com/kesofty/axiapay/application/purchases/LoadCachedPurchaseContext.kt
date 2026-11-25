package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.PurchaseContext
import com.kesofty.axiapay.domain.purchases.PurchaseService
import com.kesofty.axiapay.wallet.domain.WalletRepository

/** Opens a purchase form using the signed-in account's saved balance, catalogue and requests. */
class LoadCachedPurchaseContext(
    private val access: PurchaseSessionAccess,
    private val wallets: WalletRepository,
    private val contexts: PurchaseContextReader,
) {
    /** Performs only local reads so saved plans can appear while the device is offline. */
    suspend operator fun invoke(accountId: String, service: PurchaseService): PurchaseContext =
        access.withCachedOwner(accountId) { owner ->
            contexts.read(owner, service, wallets.cachedWallet(accountId, "NGN"))
        }
}
