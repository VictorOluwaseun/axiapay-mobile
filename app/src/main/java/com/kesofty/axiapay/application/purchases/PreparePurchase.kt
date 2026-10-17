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
        verifiedMeter: VerifiedMeter? = null,
        contactPhoneNumber: String = "",
    ): PurchaseInputCheck {
        if (product == null) {
            return PurchaseInputCheck(null, PurchaseIssue.SelectProduct)
        }
        if (!product.isSubmissionAvailable) {
            return PurchaseInputCheck(null, PurchaseIssue.ProductUnavailable)
        }
        val electricity = product.service == PurchaseService.Electricity
        val phone = canonicalPhoneNumber(if (electricity) contactPhoneNumber else recipient)
        if (!hasE164Syntax(phone)) {
            return PurchaseInputCheck(null, PurchaseIssue.InvalidRecipient)
        }
        if (electricity && !hasMeterSyntax(recipient)) {
            return PurchaseInputCheck(null, PurchaseIssue.InvalidMeter)
        }
        if (
            electricity &&
                (verifiedMeter?.validation?.productId != product.id ||
                    verifiedMeter.validation.accountNumber != recipient)
        ) {
            return PurchaseInputCheck(null, PurchaseIssue.MeterVerificationRequired)
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
            PurchaseQuoteInput(
                product.id,
                if (electricity) recipient else phone,
                minorUnits,
                product.service,
                if (electricity) verifiedMeter else null,
                if (electricity) phone else null,
            ),
            null,
        )
    }
}

/**
 * Exact printable meter identifiers are prepared once; the API verifies provider-specific rules.
 */
fun hasMeterSyntax(value: String): Boolean =
    value.length in 1..100 && value == value.trim() && value.all { it.code in 32..126 }
