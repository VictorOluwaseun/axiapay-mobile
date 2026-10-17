package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.application.canonicalPhoneNumber
import com.kesofty.axiapay.application.hasE164Syntax
import com.kesofty.axiapay.domain.purchases.*
import java.math.BigDecimal

/** Prepared input and distinct form feedback; never a financial authorization. */
data class PurchaseInputCheck(val input: PurchaseQuoteInput?, val issue: PurchaseIssue?)

/**
 * Prepare canonical recipient/exact minor units once at the UI input boundary, using published
 * product constraints for feedback.
 */
class PreparePurchase {
    /**
     * The selected API product determines the network; no prefix inference or local pricing is
     * used.
     */
    operator fun invoke(
        product: PurchaseProduct?,
        recipient: String,
        amount: String,
    ): PurchaseInputCheck {
        if (product == null) {
            return PurchaseInputCheck(null, PurchaseIssue.SelectProduct)
        }
        if (!product.isSubmissionAvailable) {
            return PurchaseInputCheck(null, PurchaseIssue.ProductUnavailable)
        }
        val phone = canonicalPhoneNumber(recipient)
        if (!hasE164Syntax(phone)) {
            return PurchaseInputCheck(null, PurchaseIssue.InvalidRecipient)
        }
        if (product.fixedMinorUnits != null) {
            return PurchaseInputCheck(
                PurchaseQuoteInput(product.id, phone, null, product.service),
                null,
            )
        }
        if (!Regex("(?:0|[1-9][0-9]{0,16})(?:\\.[0-9]{1,2})?").matches(amount)) {
            return PurchaseInputCheck(null, PurchaseIssue.InvalidAmount)
        }
        val minorUnits = BigDecimal(amount).movePointRight(2).toBigIntegerExact()
        val allowed =
            minorUnits >= product.minimumMinorUnits &&
                minorUnits <= product.maximumMinorUnits &&
                (minorUnits - product.minimumMinorUnits) % product.stepMinorUnits ==
                    java.math.BigInteger.ZERO
        if (!allowed) {
            return PurchaseInputCheck(null, PurchaseIssue.InvalidAmount)
        }
        return PurchaseInputCheck(
            PurchaseQuoteInput(product.id, phone, minorUnits, product.service),
            null,
        )
    }
}
