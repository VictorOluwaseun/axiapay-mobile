package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.application.RecipientImportPreview
import com.kesofty.axiapay.domain.ImportFileIssue
import com.kesofty.axiapay.domain.RecipientSampleFormat

/** Ephemeral import state survives rotation with its ViewModel, never process-death replay. */
data class BulkImportState(
    val id: String,
    val loading: Boolean = false,
    val preview: RecipientImportPreview? = null,
    val selectedIds: Set<String> = emptySet(),
    val failure: ImportFileIssue? = null,
    val sampleFormats: List<RecipientSampleFormat> = emptyList(),
    val sampleDownload: SampleDownloadState? = null,
    val query: String = "",
    val filter: ImportRecipientFilter = ImportRecipientFilter.All,
) {
    val rows: List<ImportRecipientRowState> =
        preview?.rows.orEmpty().map { row ->
            ImportRecipientRowState(
                row.input.id,
                row.number,
                row.input.name,
                row.input.recipientReference,
                row.input.provider,
                row.input.product,
                row.amountMinorUnits,
                row.valid,
                row.issue?.messageResource ?: row.paymentIssue?.messageResource,
            )
        }
    val readyCount: Int = rows.count { it.valid }
    val attentionCount: Int = rows.size - readyCount
    val visibleRows: List<ImportRecipientRowState> = rows.filter { row ->
        val matchesFilter =
            when (filter) {
                ImportRecipientFilter.All -> true
                ImportRecipientFilter.Ready -> row.valid
                ImportRecipientFilter.Attention -> !row.valid
                ImportRecipientFilter.Selected -> row.id in selectedIds
            }
        matchesFilter && matchesRecipient(query, row.name, row.reference, row.provider)
    }

    val canAdd: Boolean
        get() =
            !loading &&
                sampleDownload?.busy != true &&
                selectedIds.isNotEmpty() &&
                selectedIds.size <= (preview?.availableSlots ?: 0)

    val selectedTotalMinorUnits: Long =
        preview
            ?.rows
            .orEmpty()
            .filter { it.input.id in selectedIds }
            .sumOf { it.amountMinorUnits ?: 0 }
}

/** Display filters never discard invalid rows or silently change selected recipients. */
enum class ImportRecipientFilter {
    All,
    Ready,
    Attention,
    Selected,
}

/** Flattened import row exposes correction copy and terms without per-item list scans. */
data class ImportRecipientRowState(
    val id: String,
    val number: Int,
    val name: String,
    val reference: String,
    val provider: String,
    val product: String,
    val amountMinorUnits: Long?,
    val valid: Boolean,
    @androidx.annotation.StringRes val issue: Int?,
)
