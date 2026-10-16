package com.kesofty.axiapay.application.airtime

import com.kesofty.axiapay.application.canonicalPhoneNumber
import com.kesofty.axiapay.application.hasE164Syntax
import com.kesofty.axiapay.domain.airtime.*
import java.math.BigDecimal

/** Prepared input and distinct form feedback; never a financial authorization. */
data class AirtimeInputCheck(val input: AirtimeQuoteInput?, val issue: AirtimeIssue?)

/**
 * Prepare canonical recipient/exact minor units once at the UI input boundary, using published
 * product constraints for feedback.
 */
class PrepareAirtime {
    /**
     * The selected API product determines the network; no prefix inference or local pricing is
     * used.
     */
    operator fun invoke(
        product: AirtimeProduct?,
        recipient: String,
        amount: String,
    ): AirtimeInputCheck {
        if (product == null) {
            return AirtimeInputCheck(null, AirtimeIssue.SelectProduct)
        }
        if (!product.isSubmissionAvailable) {
            return AirtimeInputCheck(null, AirtimeIssue.ProductUnavailable)
        }
        val phone = canonicalPhoneNumber(recipient)
        if (!hasE164Syntax(phone)) {
            return AirtimeInputCheck(null, AirtimeIssue.InvalidRecipient)
        }
        if (product.fixedMinorUnits != null) {
            return AirtimeInputCheck(AirtimeQuoteInput(product.id, phone, null), null)
        }
        if (!Regex("(?:0|[1-9][0-9]{0,16})(?:\\.[0-9]{1,2})?").matches(amount)) {
            return AirtimeInputCheck(null, AirtimeIssue.InvalidAmount)
        }
        val minorUnits = BigDecimal(amount).movePointRight(2).toBigIntegerExact()
        val allowed =
            minorUnits >= product.minimumMinorUnits &&
                minorUnits <= product.maximumMinorUnits &&
                (minorUnits - product.minimumMinorUnits) % product.stepMinorUnits ==
                    java.math.BigInteger.ZERO
        if (!allowed) {
            return AirtimeInputCheck(null, AirtimeIssue.InvalidAmount)
        }
        return AirtimeInputCheck(AirtimeQuoteInput(product.id, phone, minorUnits), null)
    }
}
