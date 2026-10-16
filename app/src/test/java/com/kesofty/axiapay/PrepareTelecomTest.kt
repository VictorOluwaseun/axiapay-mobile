package com.kesofty.axiapay

import com.kesofty.axiapay.application.telecom.PrepareTelecom
import com.kesofty.axiapay.domain.telecom.*
import org.junit.Assert.*
import org.junit.Test

/** Exact boundary preparation uses published product constraints without local pricing. */
class PrepareTelecomTest {
    private val prepare = PrepareTelecom()
    private val product = TelecomFixture().api.product

    /** Canonical phone input and exact decimal conversion retain explicit network selection. */
    @Test
    fun canonicalInputAndExactMinorUnits() {
        val input = requireNotNull(prepare(product, "0801 234-5678", "100.50").input)
        assertEquals("+2348012345678", input.recipientPhoneNumber)
        assertEquals("10050", input.amountMinorUnits.toString())
        assertEquals(product.id, input.productId)
        val fixed =
            prepare(
                product.copy(fixedMinorUnits = 10000.toBigInteger()),
                "+2348012345678",
                "ignored",
            )
        assertNull(requireNotNull(fixed.input).amountMinorUnits)
    }

    /** Invalid decimals, increments, bounds and missing/unavailable products never reach review. */
    @Test
    fun rejectsMalformedOrOutOfRangeInput() {
        for (amount in
            listOf(
                "49",
                "10000.50",
                "100.01",
                "1e2",
                "100.001",
                "-100",
                "NaN",
                "01",
                " 100",
                "1,000",
            )) {
            assertEquals(
                amount,
                TelecomIssue.InvalidAmount,
                prepare(product, "08012345678", amount).issue,
            )
        }
        assertEquals(TelecomIssue.InvalidRecipient, prepare(product, "invalid", "100").issue)
        assertEquals(TelecomIssue.SelectProduct, prepare(null, "08012345678", "100").issue)
        assertEquals(
            TelecomIssue.ProductUnavailable,
            prepare(product.copy(isSubmissionAvailable = false), "08012345678", "100").issue,
        )
    }
}
