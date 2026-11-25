package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*
import java.time.Clock

/** Verifies an electricity meter for the selected product and signed-in account before review. */
class VerifyElectricityMeter(
    private val sessionAccess: PurchaseSessionAccess,
    private val purchases: PurchaseRepository,
    private val clock: Clock,
) {
    /** Verify selected API product/mode and bind the returned meter to the captured owner. */
    suspend operator fun invoke(
        accountId: String,
        product: PurchaseProduct,
        accountNumber: String,
    ): VerifiedMeter =
        sessionAccess.withAccount(accountId) { access ->
            val result =
                purchases.verifyMeter(access.session.accessToken, product.id, accountNumber)
            if (
                product.service != PurchaseService.Electricity ||
                    result.productId != product.id ||
                    result.accountNumber != accountNumber ||
                    result.billerCode != product.providerCode ||
                    result.meterType != product.meterType
            ) {
                throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            }
            if (result.expiresAt <= clock.instant()) {
                throw PurchaseFailure(PurchaseIssue.MeterVerificationExpired)
            }
            VerifiedMeter(access.owner, result)
        }
}
