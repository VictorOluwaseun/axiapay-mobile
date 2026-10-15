package com.kesofty.axiapay.ui.bulk

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*

/**
 * Presents complete terms and the wallet impact; only the separate confirmation button executes.
 */
@Composable
internal fun BulkReview(
    state: BulkFlowState,
    session: DemoSession,
    batch: BulkBatch?,
    onIntent: (BulkIntent) -> Unit,
) {
    val retry = state.screen == BulkScreen.RetryReview
    val recipients =
        remember(retry, batch, state.validation) {
            if (retry)
                batch
                    ?.recipients
                    ?.filter { it.status == BulkRecipientStatus.Failed }
                    ?.map { it.details }
                    .orEmpty()
            else state.validation?.recipients.orEmpty()
        }
    val visible =
        remember(recipients, state.recipientQuery) {
            recipients.filter { row ->
                matchesBulkSearch(
                    state.recipientQuery,
                    row.name,
                    row.recipientReference,
                    row.provider,
                )
            }
        }
    val total = recipients.sumOf { it.amountMinorUnits }
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.testTag("bulk-review-list"),
    ) {
        item {
            BulkHeading(
                stringResource(
                    if (retry) R.string.bulk_retry_heading else R.string.bulk_review_heading,
                ),
                stringResource(if (retry) R.string.bulk_retry_copy else R.string.bulk_review_copy),
            )
        }
        if (session.offline) item { BulkNotice(stringResource(R.string.bulk_offline_draft)) }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(DeepPine, RoundedCornerShape(24.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (retry) batch?.let { batchTitle(it) }.orEmpty()
                    else state.draft?.let { serviceBulkTitle(it.service) }.orEmpty(),
                    color = Mint,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    bulkMoney(total),
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    pluralStringResource(
                        R.plurals.bulk_recipient_count,
                        recipients.size,
                        recipients.size,
                    ),
                    color = Mint,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(18.dp),
            ) {
                Text(
                    stringResource(R.string.bulk_payment_summary),
                    style = MaterialTheme.typography.titleMedium,
                )
                InfoRow(stringResource(R.string.bulk_fee), bulkMoney(0))
                InfoRow(stringResource(R.string.bulk_available), bulkMoney(session.balance))
                InfoRow(
                    stringResource(R.string.bulk_after),
                    bulkMoney((session.balance - total).coerceAtLeast(0)),
                )
                Text(
                    stringResource(R.string.bulk_funding_source),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        if (session.balance < total)
            item {
                BulkNotice(
                    stringResource(R.string.bulk_insufficient, bulkMoney(total - session.balance)),
                    true,
                )
            }

        if (!retry)
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Lavender, RoundedCornerShape(20.dp))
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.bulk_demo_failure),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.bulk_demo_failure_copy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    val failureLabel = stringResource(R.string.bulk_demo_failure)
                    Switch(
                        state.draft?.simulateFailure == true,
                        { onIntent(BulkIntent.SimulateFailure(it)) },
                        modifier =
                            Modifier.testTag("bulk-demo-failure").semantics {
                                contentDescription = failureLabel
                            },
                    )
                }
            }
        stickyHeader(key = "review-search") {
            Surface(color = Canvas) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    BulkRecipientSearch(state.recipientQuery) {
                        onIntent(BulkIntent.SearchRecipients(it))
                    }
                    Spacer(Modifier.height(8.dp))
                    BulkResultCount(visible.size, recipients.size)
                }
            }
        }
        items(visible, key = { it.id }, contentType = { "reviewed-recipient" }) {
            ReviewedRecipient(it)
        }
        item {
            Text(
                stringResource(R.string.bulk_reservation_note),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
    }
}

/** Reads committed batch progress and exposes filters; composition never advances execution. */
@Composable
internal fun BulkProgress(
    state: BulkFlowState,
    batch: BulkBatch?,
    offline: Boolean,
    running: Boolean,
    onIntent: (BulkIntent) -> Unit,
) {
    val emptyTitle = if (running) R.string.bulk_preparing else R.string.bulk_unavailable
    if (batch == null) {
        EmptyState(
            stringResource(emptyTitle),
            stringResource(R.string.bulk_preparing_copy),
        )
        return
    }
    val heading =
        when (batch.status) {
            BulkBatchStatus.Completed -> R.string.bulk_complete_heading
            BulkBatchStatus.PartiallyCompleted -> R.string.bulk_partial_heading
            BulkBatchStatus.Failed -> R.string.bulk_failed_heading
            else -> R.string.bulk_progress_heading
        }
    val rows =
        remember(batch, state.filter, state.recipientQuery) {
            batch.recipients.filter { recipient ->
                val matchesStatus =
                    when (state.filter) {
                        BulkRecipientFilter.All -> true
                        BulkRecipientFilter.Pending ->
                            recipient.status in
                                listOf(BulkRecipientStatus.Queued, BulkRecipientStatus.Processing)
                        BulkRecipientFilter.Successful ->
                            recipient.status == BulkRecipientStatus.Successful
                        BulkRecipientFilter.Failed -> recipient.status == BulkRecipientStatus.Failed
                    }
                matchesStatus &&
                    matchesBulkSearch(
                        state.recipientQuery,
                        recipient.details.name,
                        recipient.details.recipientReference,
                        recipient.details.provider,
                    )
            }
        }
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.testTag("bulk-progress-list"),
    ) {
        item { BulkHeading(stringResource(heading), stringResource(R.string.bulk_progress_copy)) }
        if (offline && batch.hasPending)
            item { BulkNotice(stringResource(R.string.bulk_offline_pending)) }
        item { BulkProgressSummary(batch, offline) }
        stickyHeader(key = "progress-search") {
            Surface(color = Canvas) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BulkRecipientFilter.entries.forEach { filter ->
                            val label =
                                when (filter) {
                                    BulkRecipientFilter.All -> R.string.bulk_filter_all
                                    BulkRecipientFilter.Pending -> R.string.bulk_filter_pending
                                    BulkRecipientFilter.Successful ->
                                        R.string.bulk_filter_successful
                                    BulkRecipientFilter.Failed -> R.string.bulk_filter_failed
                                }
                            FilterChip(
                                state.filter == filter,
                                { onIntent(BulkIntent.Filter(filter)) },
                                { Text(stringResource(label)) },
                                border = controlBorder(state.filter == filter),
                            )
                        }
                    }
                    BulkRecipientSearch(state.recipientQuery) {
                        onIntent(BulkIntent.SearchRecipients(it))
                    }
                    Spacer(Modifier.height(8.dp))
                    BulkResultCount(rows.size, batch.recipients.size)
                }
            }
        }
        if (rows.isEmpty())
            item {
                Text(
                    stringResource(R.string.bulk_no_filter_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Muted,
                )
            }
        items(rows, key = { it.details.id }, contentType = { "progress-recipient" }) { recipient ->
            ReviewedRecipient(recipient.details) {
                val label =
                    when (recipient.status) {
                        BulkRecipientStatus.Queued -> R.string.bulk_status_queued
                        BulkRecipientStatus.Processing -> R.string.bulk_status_processing
                        BulkRecipientStatus.Successful -> R.string.bulk_status_successful
                        BulkRecipientStatus.Failed -> R.string.bulk_status_failed
                    }
                BulkStatus(stringResource(label), recipient.status == BulkRecipientStatus.Failed)
                val reason =
                    when (recipient.status) {
                        BulkRecipientStatus.Successful -> R.string.bulk_success_reason
                        BulkRecipientStatus.Failed -> R.string.bulk_failure_reason
                        else -> R.string.bulk_pending_reason
                    }
                Text(
                    stringResource(reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Text(
                    stringResource(R.string.bulk_attempt, recipient.attempts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        item { InfoRow(stringResource(R.string.bulk_reference), batch.id.takeLast(12).uppercase()) }
    }
}

/**
 * Distinguishes paid, still-reserved and released amounts; totals never imply all rows succeeded.
 */
@Composable
private fun BulkProgressSummary(batch: BulkBatch, offline: Boolean) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(batchTitle(batch), style = MaterialTheme.typography.titleLarge)
        BulkStatus(batchStatus(batch, offline), batch.failedCount > 0)
        Text(
            pluralStringResource(
                R.plurals.bulk_progress_count,
                batch.successfulCount,
                batch.successfulCount,
                batch.recipients.size,
            ),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        LinearProgressIndicator(
            progress = {
                (batch.successfulCount + batch.failedCount).toFloat() /
                    batch.recipients.size.coerceAtLeast(1)
            },
            modifier = Modifier.fillMaxWidth(),
            color = Pine,
            trackColor = Line,
        )
        InfoRow(stringResource(R.string.bulk_paid), bulkMoney(batch.paidMinorUnits))
        InfoRow(stringResource(R.string.bulk_reserved), bulkMoney(batch.reservedMinorUnits))
        InfoRow(stringResource(R.string.bulk_failed_amount), bulkMoney(batch.failedMinorUnits))
    }
}

/** Local display matching leaves the complete reviewed batch and its totals unchanged. */
private fun matchesBulkSearch(
    query: String,
    name: String,
    reference: String,
    provider: String,
): Boolean =
    query.isBlank() ||
        listOf(name, reference, provider).any { it.contains(query.trim(), ignoreCase = true) }
