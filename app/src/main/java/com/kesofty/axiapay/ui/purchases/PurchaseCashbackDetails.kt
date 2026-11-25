package com.kesofty.axiapay.ui.purchases

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.ui.theme.Pine
import com.kesofty.axiapay.wallet.ui.walletMoney

/**
 * Display a separate reward receipt from server state; never estimate commission or mutate a
 * balance.
 */
@Composable
internal fun PurchaseCashbackDetails(payment: PurchasePayment, hidden: Boolean = false) {
    if (payment.status != PurchasePaymentStatus.Succeeded) return
    val reward = payment.cashback ?: return
    val text =
        when (reward.status) {
            PurchaseCashbackStatus.NotEligible -> return
            PurchaseCashbackStatus.Pending -> stringResource(R.string.cashback_pending)
            PurchaseCashbackStatus.NotEarned -> stringResource(R.string.cashback_none)
            PurchaseCashbackStatus.Credited ->
                stringResource(
                    R.string.cashback_credited,
                    if (hidden) stringResource(R.string.home_activity_hidden_amount)
                    else
                        walletMoney(
                            requireNotNull(reward.amountMinorUnits),
                            payment.quote.currency,
                        ),
                )
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Pine,
        modifier = Modifier.testTag("purchase-cashback"),
    )
}
