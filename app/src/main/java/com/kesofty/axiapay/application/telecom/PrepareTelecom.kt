package com.kesofty.axiapay.application.telecom

import com.kesofty.axiapay.application.canonicalPhoneNumber
import com.kesofty.axiapay.application.hasE164Syntax
import com.kesofty.axiapay.domain.telecom.*
import java.math.BigDecimal

/** Prepared input and distinct form feedback; never a financial authorization. */
data class TelecomInputCheck(val input: TelecomQuoteInput?, val issue: TelecomIssue?)

/**
 * Prepare canonical recipient/exact minor units once at the UI input boundary, using published
 * product constraints for feedback.
 */
class PrepareTelecom {
    /**
     * The selected API product determines the network; no prefix inference or local pricing is
     * used.
     */
    operator fun invoke(
        product: TelecomProduct?,
        recipient: String,
        amount: String,
    ): TelecomInputCheck {
        if (product == null) {
            return TelecomInputCheck(null, TelecomIssue.SelectProduct)
        }
        if (!product.isSubmissionAvailable) {
            return TelecomInputCheck(null, TelecomIssue.ProductUnavailable)
        }
        val phone = canonicalPhoneNumber(recipient)
        if (!hasE164Syntax(phone)) {
            return TelecomInputCheck(null, TelecomIssue.InvalidRecipient)
        }
        if (product.fixedMinorUnits != null) {
            return TelecomInputCheck(
                TelecomQuoteInput(product.id, phone, null, product.service),
                null,
            )
        }
        if (!Regex("(?:0|[1-9][0-9]{0,16})(?:\\.[0-9]{1,2})?").matches(amount)) {
            return TelecomInputCheck(null, TelecomIssue.InvalidAmount)
        }
        val minorUnits = BigDecimal(amount).movePointRight(2).toBigIntegerExact()
        val allowed =
            minorUnits >= product.minimumMinorUnits &&
                minorUnits <= product.maximumMinorUnits &&
                (minorUnits - product.minimumMinorUnits) % product.stepMinorUnits ==
                    java.math.BigInteger.ZERO
        if (!allowed) {
            return TelecomInputCheck(null, TelecomIssue.InvalidAmount)
        }
        return TelecomInputCheck(
            TelecomQuoteInput(product.id, phone, minorUnits, product.service),
            null,
        )
    }
}
