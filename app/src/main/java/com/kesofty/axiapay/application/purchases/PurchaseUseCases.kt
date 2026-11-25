package com.kesofty.axiapay.application.purchases

/** Application operations for purchase preparation, consent and recovery. */
data class PurchaseUseCases(
    val loadCachedPurchaseContext: LoadCachedPurchaseContext,
    val refreshPurchaseContext: RefreshPurchaseContext,
    val preparePurchase: PreparePurchase,
    val verifyElectricityMeter: VerifyElectricityMeter,
    val reviewPurchase: ReviewPurchase,
    val submitPurchase: SubmitPurchase,
    val recoverPurchase: RecoverPurchase,
    val reconcilePurchase: ReconcilePurchase,
    val acknowledgePurchase: AcknowledgePurchase,
)
