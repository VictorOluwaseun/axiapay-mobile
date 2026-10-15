package com.kesofty.axiapay.ui.bulk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.money
import com.kesofty.axiapay.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A prominent Payments entry uses the established palette and exposes any unfinished draft. */
@Composable
fun BulkEntryCard(draftCount: Int?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(DeepPine)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(22.dp)
            .testTag("bulk-entry"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Glyph.Batch, tint = Mint)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    stringResource(R.string.bulk_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    stringResource(R.string.bulk_entry_services),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mint,
                )
            }
        }
        Text(
            stringResource(R.string.bulk_entry_copy),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Text(
            stringResource(R.string.bulk_capacity_highlight),
            style = MaterialTheme.typography.bodyLarge,
            color = Mint,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (draftCount == null) stringResource(R.string.bulk_create)
                else pluralStringResource(R.plurals.bulk_continue_draft, draftCount, draftCount),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = Mint,
            )
            AxiaIcon(Glyph.ArrowRight, color = Mint)
        }
    }
}

/** Readable flow headings keep the established Manrope hierarchy without fixed text heights. */
@Composable
internal fun BulkHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(description, style = MaterialTheme.typography.bodyLarge, color = Muted)
    }
}

/** Displays contextual offline, validation or demo information in both colour modes. */
@Composable
internal fun BulkNotice(message: String, isError: Boolean = false) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (isError) Danger.copy(alpha = .07f) else Lavender,
                RoundedCornerShape(18.dp),
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AxiaIcon(if (isError) Glyph.Help else Glyph.Shield, color = if (isError) Danger else Pine)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) Danger else Ink,
        )
    }
}

/** Gives status meaning through text as well as colour, including in greyscale. */
@Composable
internal fun BulkStatus(label: String, failed: Boolean = false) {
    val color = if (failed) Danger else Pine
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier =
            Modifier.background(color.copy(alpha = .08f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Maps supported domain services to human-readable names. */
@Composable
internal fun serviceLabel(service: BulkService): String =
    stringResource(
        when (service) {
            BulkService.Airtime -> R.string.bulk_airtime
            BulkService.Data -> R.string.bulk_data
            BulkService.Electricity -> R.string.bulk_electricity
            BulkService.TV -> R.string.bulk_tv
        },
    )

/** Reuses established outlined service icons for all recipient rows. */
internal fun serviceIcon(service: BulkService): Glyph =
    when (service) {
        BulkService.Airtime -> Glyph.Phone
        BulkService.Data -> Glyph.Wifi
        BulkService.Electricity -> Glyph.Bolt
        BulkService.TV -> Glyph.Tv
    }

/** Maps aggregate state without claiming that a partly completed batch has fully succeeded. */
@Composable
internal fun batchStatus(batch: BulkBatch, offline: Boolean): String {
    if (offline && batch.hasPending) return stringResource(R.string.bulk_status_paused)
    return stringResource(
        when (batch.status) {
            BulkBatchStatus.Queued -> R.string.bulk_status_queued
            BulkBatchStatus.Processing -> R.string.bulk_status_processing
            BulkBatchStatus.Completed -> R.string.bulk_status_complete
            BulkBatchStatus.PartiallyCompleted -> R.string.bulk_status_partial
            BulkBatchStatus.Failed -> R.string.bulk_status_failed
        },
    )
}

/** A compact Activity row opens the same persisted progress screen used after confirmation. */
@Composable
fun BulkHistoryCard(batch: BulkBatch, hidden: Boolean, offline: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .border(controlBorder(), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(18.dp)
            .testTag("batch-${batch.id}"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(Glyph.Batch, tint = Lavender)
            Column(Modifier.weight(1f)) {
                Text(batchTitle(batch), style = MaterialTheme.typography.titleMedium)
                Text(
                    batch.createdAt
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            AxiaIcon(Glyph.Chevron)
        }
        Text(
            if (hidden) "••••" else bulkMoney(batch.totalMinorUnits),
            style = MaterialTheme.typography.headlineMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BulkStatus(batchStatus(batch, offline), batch.failedCount > 0)
            Text(
                pluralStringResource(
                    R.plurals.bulk_progress_count,
                    batch.successfulCount,
                    batch.successfulCount,
                    batch.recipients.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/** Shows reviewed service/account terms without truncating identifiers or hiding currency. */
@Composable
internal fun ReviewedRecipient(
    details: BulkRecipientDetails,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(serviceIcon(details.service))
            Column(Modifier.weight(1f)) {
                Text(details.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${details.provider} · ${serviceLabel(details.service)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        if (details.name != details.recipientReference)
            Text(details.recipientReference, style = MaterialTheme.typography.bodyLarge)
        if (details.product.isNotBlank())
            Text(details.product, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(bulkMoney(details.amountMinorUnits), style = MaterialTheme.typography.titleLarge)
        content()
    }
}

/** Shows every kobo in reviewed and settled totals instead of rounding fractional naira away. */
internal fun bulkMoney(amountMinorUnits: Long): String = money(amountMinorUnits, decimals = true)

/** Localizes automatically named service batches, preserving older user-named history. */
@Composable
internal fun batchTitle(batch: BulkBatch): String {
    val service = BulkService.entries.firstOrNull { it.name == batch.title }
    return service?.let { serviceBulkTitle(it) } ?: batch.title
}

/** Uses resource-backed titles for quick lists and automatically named Activity records. */
@Composable
internal fun serviceBulkTitle(service: BulkService): String =
    stringResource(
        when (service) {
            BulkService.Airtime -> R.string.bulk_airtime_title
            BulkService.Data -> R.string.bulk_data_title
            BulkService.Electricity -> R.string.bulk_electricity_title
            BulkService.TV -> R.string.bulk_tv_title
        },
    )
