package com.kesofty.axiapay.ui

import androidx.annotation.StringRes
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.wallet.WalletIssue
import com.kesofty.axiapay.domain.wallet.WalletStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Format exact minor units with caller-selected presentation locale and no floating-point
 * conversion.
 */
internal fun walletMoney(
    minorUnits: BigInteger,
    currencyCode: String,
    locale: Locale = Locale.getDefault(),
): String {
    val formatter = NumberFormat.getCurrencyInstance(locale)
    formatter.currency = Currency.getInstance(currencyCode)
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    return formatter.format(BigDecimal(minorUnits, 2))
}

/** Resolve consistent, safe wallet feedback in both account and dashboard views. */
@StringRes
internal fun walletIssueMessage(issue: WalletIssue): Int =
    when (issue) {
        WalletIssue.Missing -> R.string.wallet_missing
        WalletIssue.Network -> R.string.wallet_network_error
        else -> R.string.wallet_unavailable
    }

/** Present the server lifecycle label without deriving spending permissions. */
@StringRes
internal fun walletStatusLabel(status: WalletStatus): Int =
    when (status) {
        WalletStatus.Active -> R.string.wallet_active
        WalletStatus.Frozen -> R.string.wallet_frozen
        WalletStatus.Closed -> R.string.wallet_closed
    }
