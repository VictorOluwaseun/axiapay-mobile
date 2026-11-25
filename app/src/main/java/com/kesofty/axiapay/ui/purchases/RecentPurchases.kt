package com.kesofty.axiapay.ui.purchases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.purchases.PurchaseActivityState
import com.kesofty.axiapay.ui.components.Glyph
import com.kesofty.axiapay.ui.components.IconTile
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.wallet.ui.walletMoney
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Show the latest three server observations; missing or failed reads never become demo payments.
 */
@Composable
fun RecentPurchases(
    state: PurchaseActivityState,
    hidden: Boolean,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onOpenReceipt: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("home-live-activity")) {
        if (state.accountId == null) {
            TextButton(onClick = onSignIn) { Text(stringResource(R.string.auth_sign_in)) }
            return@Column
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("home-activity-loading"))
        }
        if (state.issue != null) {
            Text(stringResource(R.string.live_activity_error), color = Danger)
        }
        if (!state.loading && state.issue == null && state.entries.isEmpty()) {
            Text(
                stringResource(R.string.live_activity_empty),
                color = Muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        state.entries.take(3).forEach { entry ->
            key(entry.request.requestKey) {
                RecentPurchaseRow(entry, hidden) { onOpenReceipt(entry.request.requestKey) }
            }
        }
        TextButton(
            onClick = onRefresh,
            enabled = !state.loading,
            modifier = Modifier.testTag("home-activity-refresh"),
        ) {
            Text(stringResource(R.string.live_activity_refresh))
        }
    }
}

/**
 * Keep purchase amount, status and credited cashback distinct; tapping opens the selected read-only
 * receipt.
 */
@Composable
private fun RecentPurchaseRow(entry: PurchaseProgress, hidden: Boolean, onOpenReceipt: () -> Unit) {
    val quote = entry.quote
    val glyph =
        when (entry.request.service) {
            PurchaseService.Airtime -> Glyph.Phone
            PurchaseService.Data -> Glyph.Wifi
            PurchaseService.Electricity -> Glyph.Bolt
        }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackDetails = maxWidth.value / fontScale < 300f
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(role = Role.Button, onClick = onOpenReceipt)
                .testTag("home-purchase-row")
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconTile(glyph)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    maxItemsInEachRow = if (stackDetails) 1 else 2,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        quote?.displayName ?: stringResource(R.string.live_activity_unknown),
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    quote?.let {
                        Text(
                            if (hidden) stringResource(R.string.home_activity_hidden_amount)
                            else walletMoney(it.totalDebitMinorUnits, it.currency),
                            style = MaterialTheme.typography.titleSmall,
                            color = Ink,
                            modifier = Modifier.testTag("home-purchase-amount"),
                        )
                    }
                }
                PurchaseActivityStatus(entry.payment?.status, compact = true)
                Text(
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault())
                        .format(entry.payment?.createdAt ?: entry.request.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                entry.payment?.let { PurchaseCashbackDetails(it, hidden) }
            }
        }
    }
}
