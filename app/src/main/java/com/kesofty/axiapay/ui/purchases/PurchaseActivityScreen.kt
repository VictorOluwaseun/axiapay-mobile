package com.kesofty.axiapay.ui.purchases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.purchases.PurchaseActivityState
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.wallet.ui.walletMoney
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Display authenticated purchase observations from this device; refresh is strictly read-only. */
@Composable
fun PurchaseActivityScreen(
    state: PurchaseActivityState,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onOpenReceipt: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.testTag("live-activity"),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.live_activity_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
            )
            Text(
                stringResource(R.string.live_activity_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
        if (state.accountId == null) {
            item { Button(onClick = onSignIn) { Text(stringResource(R.string.auth_sign_in)) } }
        } else {
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.loading,
                    modifier = Modifier.testTag("live-activity-refresh"),
                ) {
                    Text(stringResource(R.string.live_activity_refresh))
                }
            }
        }
        if (state.loading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (state.issue != null) {
            item { Text(stringResource(R.string.live_activity_error), color = Danger) }
        }
        if (
            state.accountId != null &&
                !state.loading &&
                state.issue == null &&
                state.entries.isEmpty()
        ) {
            item { Text(stringResource(R.string.live_activity_empty), color = Muted) }
        }
        items(state.entries, key = { it.request.requestKey }) { entry ->
            PurchaseActivityRow(entry) { onOpenReceipt(entry.request.requestKey) }
        }
    }
}

/**
 * Show server status, exact debit and receipt identity without converting pending progress into
 * success.
 */
@Composable
private fun PurchaseActivityRow(entry: PurchaseProgress, onOpenReceipt: () -> Unit) {
    val quote = entry.quote
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClick = onOpenReceipt)
                .testTag("live-activity-row"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                quote?.displayName ?: stringResource(R.string.live_activity_unknown),
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            quote?.let {
                Text(
                    it.recipientIdentifier,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Text(
                    walletMoney(it.totalDebitMinorUnits, it.currency),
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                )
            }
            PurchaseActivityStatus(entry.payment?.status)
            entry.payment?.let { PurchaseCashbackDetails(it) }
            Text(
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withZone(ZoneId.systemDefault())
                    .format(entry.payment?.createdAt ?: entry.request.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            entry.payment?.let {
                Text(
                    stringResource(R.string.telecom_payment_reference, it.id),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

/** Share server outcome labels across Home and Activity without deriving payment success. */
@Composable
internal fun PurchaseActivityStatus(status: PurchasePaymentStatus?, compact: Boolean = false) {
    val statusResource =
        when (status) {
            PurchasePaymentStatus.Succeeded -> R.string.live_activity_succeeded
            PurchasePaymentStatus.Failed -> R.string.live_activity_failed
            PurchasePaymentStatus.Pending,
            PurchasePaymentStatus.Reserved -> R.string.live_activity_pending
            PurchasePaymentStatus.AwaitingFunds -> R.string.live_activity_awaiting_funds
            null -> R.string.live_activity_unconfirmed
        }
    val statusColor =
        when (status) {
            PurchasePaymentStatus.Succeeded -> Pine
            PurchasePaymentStatus.Failed -> Danger
            else -> Warning
        }
    Text(
        stringResource(statusResource),
        color = statusColor,
        style =
            if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("live-activity-status"),
    )
}
