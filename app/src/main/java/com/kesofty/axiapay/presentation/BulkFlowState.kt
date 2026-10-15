package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.BulkService

/** Choosing a service opens one quick list; review and progress never execute work on rendering. */
enum class BulkScreen {
    Services,
    Recipients,
    Import,
    Review,
    Progress,
    RetryReview,
}

/** Per-recipient filters are independent of Activity's history filter. */
enum class BulkRecipientFilter {
    All,
    Pending,
    Successful,
    Failed,
}

/** Retains an inactive service's saved rows and unfinished inline edit without spending funds. */
data class BulkSavedDraft(val draft: BulkDraft, val editor: BulkRecipientInput? = null)

/** One owner retains the active list and at most one other draft per service across recreation. */
data class BulkFlowState(
    val screen: BulkScreen? = null,
    val draft: BulkDraft? = null,
    val editor: BulkRecipientInput? = null,
    val savedDrafts: List<BulkSavedDraft> = emptyList(),
    val batchId: String? = null,
    val importState: BulkImportState? = null,
    val showErrors: Boolean = false,
    val editorIssue: BulkInputIssue? = null,
    val validation: BulkValidation? = null,
    val filter: BulkRecipientFilter = BulkRecipientFilter.All,
    val editorProviders: List<String> = emptyList(),
    val editorProducts: List<String> = emptyList(),
    val editorAmountMinorUnits: Long? = null,
    val previewTotalMinorUnits: Long = 0,
    val recipientQuery: String = "",
    val visibleRecipients: List<BulkRecipientRow> = emptyList(),
    val selectingRecipients: Boolean = false,
    val selectedRecipientIds: Set<String> = emptySet(),
    val confirmingRemoval: Boolean = false,
    val providerPicker: ProviderPickerState = ProviderPickerState(),
) {
    /** Keeps the unavailable international route outside this list's valid provider terms. */
    val showsInternationalAirtime
        get() = editor?.service == BulkService.Airtime
}

/** Explicit edits and commands enter the same ViewModel; only confirmation can reserve funds. */
sealed interface BulkIntent {
    /** Routes electricity search without modifying the recipient editor or reviewed terms. */
    data class ProviderPicker(val intent: ProviderPickerIntent) : BulkIntent

    /** Filters display only; the reviewed total always includes the complete draft. */
    data class SearchRecipients(val query: String) : BulkIntent

    /** Opens or closes explicit multi-selection of unsubmitted rows. */
    data object ToggleRecipientSelection : BulkIntent

    /** Changes selection by stable identity, independently of filtering. */
    data class SelectRecipient(val id: String) : BulkIntent

    /** Selects the currently visible unsubmitted rows without modifying hidden selection. */
    data object SelectVisibleRecipients : BulkIntent

    /** Requests a count-labelled confirmation before removing draft recipients. */
    data object RequestRemoveSelected : BulkIntent

    /** Applies the explicitly confirmed removal, or dismisses it without changing input. */
    data class RemoveSelected(val confirmed: Boolean) : BulkIntent

    /** Changes import display, without changing selection or reading the document again. */
    data class SearchImport(val query: String) : BulkIntent

    /** Isolates ready or invalid source rows for quick review. */
    data class FilterImport(val filter: ImportRecipientFilter) : BulkIntent

    /** Selects valid rows up to available capacity, or clears the selection explicitly. */
    data class SelectImportRows(val select: Boolean) : BulkIntent

    /** Routes the independent sample-file workflow without mutating payment terms. */
    data class SampleDownload(val intent: SampleDownloadIntent) : BulkIntent

    /** Opens a transient local import session without committing unfinished input. */
    data object OpenImport : BulkIntent

    /** Delivers a one-time picker result only to its originating import session. */
    data class ImportFile(val sessionId: String, val reference: String) : BulkIntent

    /** Selects or deselects a validated source row; invalid rows cannot be included. */
    data class SelectImportRow(val id: String) : BulkIntent

    /** Adds only selected valid rows, preserving the editor and existing recipients. */
    data object AddImportedRecipients : BulkIntent

    /** Reports an unavailable system document picker without changing the draft. */
    data object ImportPickerUnavailable : BulkIntent

    /** Shows service choices, retaining each unsubmitted draft. */
    data object Open : BulkIntent

    /** Starts or resumes the selected service's quick list. */
    data class ChooseService(val service: BulkService) : BulkIntent

    /** Moves the current unscheduled individual form into its matching quick list. */
    data object FromPayment : BulkIntent

    /** Opens retained execution history without restarting it. */
    data class OpenBatch(val id: String) : BulkIntent

    /** Saves the current inline edit and opens another row using its previous terms. */
    data object AddAnother : BulkIntent

    /** Saves the current valid edit and returns to the list without requiring payment review. */
    data object SaveRecipient : BulkIntent

    /** Expands an existing row in place after validating any unfinished edit. */
    data class EditRecipient(val id: String) : BulkIntent

    /** Replaces raw inline input; the service is fixed by its draft. */
    data class RecipientValue(val value: BulkRecipientInput) : BulkIntent

    /** Removes only the addressed unsubmitted row. */
    data class RemoveRecipient(val id: String) : BulkIntent

    /** Adds labelled examples for the current service without replacing manual entries. */
    data object LoadSamples : BulkIntent

    /** Selects the demo result scenario before confirmation. */
    data class SimulateFailure(val enabled: Boolean) : BulkIntent

    /** Includes the inline edit and reviews all validated recipients. */
    data object Review : BulkIntent

    /** Explicitly confirms the reviewed reservation and demo run. */
    data object Submit : BulkIntent

    /** Reviews only known failures without reserving funds. */
    data object ReviewRetry : BulkIntent

    /** Confirms the failed-only retry total. */
    data object ConfirmRetry : BulkIntent

    /** Resumes retained pending work explicitly; reconnection never emits this. */
    data object Resume : BulkIntent

    /** Returns to editing or service selection without discarding drafts. */
    data object Back : BulkIntent

    /** Leaves the flow retaining every service draft. */
    data object Close : BulkIntent

    /** Opens Activity's batch history. */
    data object History : BulkIntent

    /** Changes only visible progress rows. */
    data class Filter(val value: BulkRecipientFilter) : BulkIntent
}
