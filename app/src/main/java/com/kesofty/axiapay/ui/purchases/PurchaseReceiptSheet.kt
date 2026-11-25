package com.kesofty.axiapay.ui.purchases

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.ui.SheetContent
import com.kesofty.axiapay.ui.components.PrimaryButton
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.wallet.ui.walletMoney
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Show an existing authenticated observation with read-only refresh and dismissal. This sheet has
 * no submission, acknowledgement or reconciliation callback and keeps receipt data out of routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseReceiptSheet(
    entry: PurchaseProgress,
    loading: Boolean,
    issue: PurchaseIssue?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Line) },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("purchase-receipt")) {
                SheetContent {
                    Text(
                        stringResource(R.string.purchase_receipt_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Ink,
                    )
                    PurchaseActivityStatus(entry.payment?.status)
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (issue != null || entry.issue != null) {
                        Text(stringResource(R.string.purchase_receipt_error), color = Danger)
                    }
                    PurchaseReceiptDetails(entry)
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().testTag("purchase-receipt-refresh"),
                    ) {
                        Text(stringResource(R.string.purchase_receipt_refresh))
                    }
                    PrimaryButton(
                        stringResource(R.string.telecom_done),
                        Modifier.testTag("purchase-receipt-done"),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

/** Keep exact quoted charges separate from the server-confirmed payment outcome and cashback. */
@Composable
private fun PurchaseReceiptDetails(entry: PurchaseProgress) {
    val payment = entry.payment
    val quote = payment?.quote ?: entry.quote
    val unavailable = stringResource(R.string.purchase_receipt_unavailable)
    ReceiptField(
        stringResource(R.string.telecom_product),
        quote?.displayName ?: unavailable,
        "purchase-receipt-product",
    )
    ReceiptField(
        stringResource(R.string.purchase_receipt_recipient),
        quote?.recipientIdentifier ?: unavailable,
        "purchase-receipt-recipient",
    )
    ReceiptField(
        stringResource(R.string.purchase_receipt_amount),
        quote?.let { walletMoney(it.principalMinorUnits, it.currency) } ?: unavailable,
        "purchase-receipt-amount",
    )
    ReceiptField(
        stringResource(R.string.telecom_fee),
        quote?.let { walletMoney(it.feeMinorUnits, it.currency) } ?: unavailable,
        "purchase-receipt-fee",
    )
    ReceiptField(
        stringResource(
            if (payment?.status == PurchasePaymentStatus.Succeeded) R.string.purchase_receipt_debit
            else R.string.telecom_total,
        ),
        quote?.let { walletMoney(it.totalDebitMinorUnits, it.currency) } ?: unavailable,
        "purchase-receipt-total",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.purchase_receipt_cashback),
            style = MaterialTheme.typography.labelLarge,
            color = Muted,
        )
        when {
            payment?.status != PurchasePaymentStatus.Succeeded || payment.cashback == null ->
                Text(unavailable, color = Muted, modifier = Modifier.testTag("purchase-cashback"))
            payment.cashback.status == PurchaseCashbackStatus.NotEligible ->
                Text(
                    stringResource(R.string.purchase_receipt_cashback_ineligible),
                    color = Muted,
                    modifier = Modifier.testTag("purchase-cashback"),
                )
            else -> PurchaseCashbackDetails(payment)
        }
    }
    ReceiptField(
        stringResource(R.string.purchase_receipt_date),
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(payment?.createdAt ?: entry.request.createdAt),
        "purchase-receipt-date",
    )
    ReceiptField(
        stringResource(R.string.purchase_receipt_reference),
        payment?.id ?: unavailable,
        "purchase-receipt-reference",
    )
    if (payment == null) {
        ReceiptField(
            stringResource(R.string.purchase_receipt_request_reference),
            entry.request.requestKey,
            "purchase-receipt-request",
        )
    }
    payment?.let { ElectricityFulfilment(it) }
}

/** Stack labels and selectable values so long references and larger text stay readable. */
@Composable
private fun ReceiptField(label: String, value: String, tag: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
        SelectionContainer {
            Text(
                value,
                Modifier.testTag(tag),
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
            )
        }
    }
}
