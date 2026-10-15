package com.kesofty.axiapay.presentation

/** Presentation-only modal state; the surrounding payment form owns the selected provider. */
data class ProviderPickerState(val isOpen: Boolean = false, val query: String = "")

/** Explicit UI actions never submit, validate or change payment terms. */
sealed interface ProviderPickerIntent {
    /** Starts a fresh search without changing the current provider. */
    data object Open : ProviderPickerIntent

    /** Closes the modal and clears its temporary search. */
    data object Close : ProviderPickerIntent

    /** Filters available display choices without changing selection. */
    data class Search(val query: String) : ProviderPickerIntent
}

/** Reduces bounded display state under the existing ViewModel's immutable navigation owner. */
internal fun reduceProviderPicker(
    state: ProviderPickerState,
    intent: ProviderPickerIntent,
): ProviderPickerState =
    when (intent) {
        ProviderPickerIntent.Open -> ProviderPickerState(isOpen = true)
        ProviderPickerIntent.Close -> ProviderPickerState()
        is ProviderPickerIntent.Search ->
            if (state.isOpen) state.copy(query = intent.query.take(80)) else state
    }
