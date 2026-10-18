package com.kesofty.axiapay

import com.kesofty.axiapay.ui.walletMoney
import java.math.BigInteger
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Protects the demo's naira presentation while preserving exact server-provided minor units. */
class WalletPresentationTest {
    /** Dashboard amounts use the same naira symbol and two decimal places as the demo. */
    @Test
    fun dashboardNairaFormattingPreservesMinorUnits() {
        val locale = Locale.forLanguageTag("en-NG")
        assertEquals("₦0.00", walletMoney(BigInteger.ZERO, "NGN", locale))
        assertEquals("₦0.01", walletMoney(BigInteger.ONE, "NGN", locale))
        assertEquals("₦125.00", walletMoney(BigInteger.valueOf(12500), "NGN", locale))
    }

    /** Large API amounts must retain every digit without a Long or floating-point conversion. */
    @Test
    fun dashboardFormattingPreservesBalancesBeyondLongRange() {
        assertEquals(
            "₦123,456,789,012,345,678.90",
            walletMoney(BigInteger("12345678901234567890"), "NGN", Locale.forLanguageTag("en-NG")),
        )
    }
}
