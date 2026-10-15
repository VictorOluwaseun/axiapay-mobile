package com.kesofty.axiapay.ui.bulk

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.BulkService
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.PrimaryButton
import com.kesofty.axiapay.ui.theme.*

/**
 * Launches the system picker only on a tap; its result is bound to this explicit import session.
 */
@Composable
internal fun BulkImportScreen(flow: BulkFlowState, emit: (BulkIntent) -> Unit) {
    val state = flow.importState ?: return
    val service = flow.draft?.service ?: return
    // Keep the result registration alive even when its card scrolls outside the lazy viewport.
    key(state.sampleDownload?.id) { SampleSavePicker(state.sampleDownload, emit) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) emit(BulkIntent.ImportFile(state.id, uri.toString()))
        }
    val chooseFile: () -> Unit = {
        try {
            picker.launch(
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "application/csv",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
        } catch (_: ActivityNotFoundException) {
            emit(BulkIntent.ImportPickerUnavailable)
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.testTag("bulk-import-list"),
    ) {
        if (state.preview == null)
            item {
                BulkHeading(
                    stringResource(R.string.import_heading),
                    stringResource(R.string.import_copy, serviceLabel(service)),
                )
            }
        if (state.preview == null)
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Mint, RoundedCornerShape(22.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.import_formats),
                        style = MaterialTheme.typography.titleLarge,
                        color = Pine,
                    )
                    Text(
                        stringResource(R.string.import_limits),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Pine,
                    )
                    PrimaryButton(
                        stringResource(R.string.import_choose),
                        enabled = !state.loading && state.sampleDownload?.busy != true,
                        onClick = chooseFile,
                    )
                }
            }
        if (state.preview == null && state.sampleFormats.isNotEmpty())
            item { ImportSampleDownloads(state, emit) }
        if (state.loading)
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Pine)
                    Text(
                        stringResource(R.string.import_reading),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        state.failure?.let { issue ->
            item { BulkNotice(stringResource(issue.messageResource), true) }
        }
        importPreviewItems(state, emit, chooseFile)
        if (state.preview == null) item { ImportColumnGuide(service, flow) }
    }
}

/** Keeps import search/selection pinned while large preview rows remain individually lazy. */
private fun LazyListScope.importPreviewItems(
    state: BulkImportState,
    emit: (BulkIntent) -> Unit,
    chooseFile: () -> Unit,
) {
    if (state.preview == null) return
    item { ImportPreviewSummary(state, chooseFile) }
    stickyHeader(key = "controls") {
        Surface(color = Canvas) {
            Column(Modifier.padding(vertical = 8.dp)) { ImportListControls(state, emit) }
        }
    }
    if (state.visibleRows.isEmpty())
        item { BulkNotice(stringResource(R.string.bulk_no_search_results)) }
    items(state.visibleRows, key = { it.id }, contentType = { "import-row" }) { row ->
        ImportRecipientRow(row, row.id in state.selectedIds, emit)
    }
    item { BulkNotice(stringResource(R.string.import_skipped_hint)) }
}

/** One accessible checkbox target shows the original row number, complete terms and correction. */
@Composable
private fun ImportRecipientRow(
    row: ImportRecipientRowState,
    selected: Boolean,
    emit: (BulkIntent) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(18.dp))
            .toggleable(selected, enabled = row.valid, role = Role.Checkbox) {
                emit(BulkIntent.SelectImportRow(row.id))
            }
            .padding(16.dp)
            .testTag("import-row-${row.number}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.import_row, row.number),
                    style = MaterialTheme.typography.labelLarge,
                    color = Muted,
                )
                Text(
                    row.name.ifBlank {
                        row.reference.ifBlank {
                            stringResource(R.string.import_missing_recipient)
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Checkbox(selected, onCheckedChange = null, enabled = row.valid)
        }
        if (row.name.isNotBlank()) Text(row.reference, style = MaterialTheme.typography.bodyLarge)
        Text(
            listOf(
                    row.provider,
                    row.product,
                    row.amountMinorUnits?.let(::bulkMoney).orEmpty(),
                )
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        val issue = row.issue
        if (issue != null) BulkNotice(stringResource(issue), true)
    }
}

/** File identity and capacity exceptions stay concise so recipients appear near the top. */
@Composable
private fun ImportPreviewSummary(state: BulkImportState, chooseFile: () -> Unit) {
    val preview = state.preview ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(preview.fileName, style = MaterialTheme.typography.titleLarge)
        preview.sheetName?.let {
            Text(
                stringResource(R.string.import_sheet, it),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
        Text(
            stringResource(
                R.string.import_counts,
                pluralStringResource(
                    R.plurals.import_ready_count,
                    state.readyCount,
                    state.readyCount,
                ),
                pluralStringResource(
                    R.plurals.import_attention_count,
                    state.attentionCount,
                    state.attentionCount,
                ),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = Pine,
        )
        TextButton(chooseFile) { Text(stringResource(R.string.import_replace)) }
        if (state.selectedIds.size > preview.availableSlots)
            BulkNotice(
                pluralStringResource(
                    R.plurals.import_capacity,
                    preview.availableSlots,
                    preview.availableSlots,
                ),
                true,
            )
    }
}

/** Fast file-level selection and issue filters work independently of the visible search results. */
@Composable
private fun ImportListControls(state: BulkImportState, emit: (BulkIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BulkRecipientSearch(state.query) { emit(BulkIntent.SearchImport(it)) }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ImportRecipientFilter.entries.forEach { filter ->
                val label =
                    when (filter) {
                        ImportRecipientFilter.All -> stringResource(R.string.bulk_filter_all)
                        ImportRecipientFilter.Ready ->
                            stringResource(R.string.import_ready_filter, state.readyCount)
                        ImportRecipientFilter.Attention ->
                            stringResource(R.string.import_attention_filter, state.attentionCount)
                        ImportRecipientFilter.Selected ->
                            stringResource(R.string.import_selected_filter, state.selectedIds.size)
                    }
                FilterChip(
                    state.filter == filter,
                    { emit(BulkIntent.FilterImport(filter)) },
                    { Text(label) },
                    border = controlBorder(state.filter == filter),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ emit(BulkIntent.SelectImportRows(true)) }) {
                Text(
                    stringResource(
                        R.string.import_select_available,
                        minOf(state.readyCount, state.preview?.availableSlots ?: 0),
                    ),
                )
            }
            TextButton({ emit(BulkIntent.SelectImportRows(false)) }) {
                Text(stringResource(R.string.import_clear_selection))
            }
        }
        BulkResultCount(state.visibleRows.size, state.rows.size)
    }
}

/** Makes the expected source columns and demo catalog explicit before users choose a document. */
@Composable
private fun ImportColumnGuide(service: BulkService, flow: BulkFlowState) {
    val headers =
        when (service) {
            BulkService.Airtime -> R.string.import_headers_airtime
            BulkService.Data,
            BulkService.TV -> R.string.import_headers_package
            BulkService.Electricity -> R.string.import_headers_electricity
        }
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.import_guide_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(headers), style = MaterialTheme.typography.bodyLarge, color = Pine)
        Text(
            stringResource(R.string.import_guide_copy),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        Text(
            stringResource(
                R.string.import_provider_options,
                flow.editorProviders.joinToString(", "),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (flow.editorProducts.isNotEmpty())
            Text(
                stringResource(
                    R.string.import_product_options,
                    flow.editorProducts.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        Text(
            stringResource(R.string.import_text_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
    }
}

/**
 * Adding selected recipients only updates the draft; payment still requires the existing review.
 */
@Composable
internal fun BulkImportFooter(flow: BulkFlowState, emit: (BulkIntent) -> Unit) {
    val state = flow.importState ?: return
    Text(
        pluralStringResource(
            R.plurals.import_selected,
            state.selectedIds.size,
            state.selectedIds.size,
            bulkMoney(state.selectedTotalMinorUnits),
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    PrimaryButton(
        stringResource(R.string.import_add, state.selectedIds.size),
        enabled = state.canAdd,
    ) {
        emit(BulkIntent.AddImportedRecipients)
    }
}
