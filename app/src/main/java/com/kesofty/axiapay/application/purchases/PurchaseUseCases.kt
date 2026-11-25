package com.kesofty.axiapay.application.purchases

/** Purchase form operations for loading display data and handling explicit payment actions. */
data class PurchaseUseCases(
    val loadCachedPurchaseContext: LoadCachedPurchaseContext,
    val refreshPurchaseContext: RefreshPurchaseContext,
    val preparePurchase: PreparePurchase,
    val payments: WalletPurchases,
)
