package com.kesofty.axiapay.presentation.purchases

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.purchases.PurchaseUseCases
import com.kesofty.axiapay.domain.purchases.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Account-owned observations shared by Home and Activity; credentials and receipts never enter
 * saved state.
 */
data class PurchaseActivityState(
    val accountId: String? = null,
    val visible: Boolean = false,
    val loading: Boolean = false,
    val entries: List<PurchaseProgress> = emptyList(),
    val issue: PurchaseIssue? = null,
    val selectedRequestKey: String? = null,
) {
    /** Resolve the receipt from the latest observation rather than keeping a stale second copy. */
    val selectedEntry: PurchaseProgress?
        get() = entries.firstOrNull { it.request.requestKey == selectedRequestKey }

    /** Hide a previous account during the frame before its binding intent is consumed. */
    fun forAccount(accountId: String?): PurchaseActivityState =
        if (this.accountId == accountId) this
        else PurchaseActivityState(accountId = accountId, loading = accountId != null)

    /** Keep recipients and amounts out of state diagnostics. */
    override fun toString() = "PurchaseActivityState(loading=$loading, details=redacted)"
}

/** Navigation and refresh read existing purchases; this feature has no spending intent. */
sealed interface PurchaseActivityIntent {
    /** Bind the current account and whether Home or Activity can display purchase history. */
    data class Bind(val accountId: String?, val visible: Boolean) : PurchaseActivityIntent

    /** Refresh current server outcomes without executing or reconciling payments. */
    data object Refresh : PurchaseActivityIntent

    /** Select an observed purchase for a read-only receipt without changing its payment state. */
    data class OpenReceipt(val requestKey: String) : PurchaseActivityIntent

    /** Dismiss the receipt and keep the originating Home or Activity tab. */
    data object CloseReceipt : PurchaseActivityIntent
}

/**
 * Own read-only Home and Activity history independently of editable purchase forms and demo
 * fixtures.
 */
@HiltViewModel
class PurchaseActivityViewModel @Inject constructor(feature: Optional<PurchaseUseCases>) :
    ViewModel() {
    private val loadPurchaseHistory = feature.orElse(null)?.loadPurchaseHistory
    private val mutableState = MutableStateFlow(PurchaseActivityState())
    val uiState = mutableState.asStateFlow()
    private var action: Job? = null
    private var generation = 0L

    /**
     * Cancel stale visits and publish observations only for the same account and visibility scope.
     */
    @MainThread
    fun accept(intent: PurchaseActivityIntent) {
        if (loadPurchaseHistory == null) {
            return
        }
        when (intent) {
            is PurchaseActivityIntent.Bind -> {
                val current = mutableState.value
                if (current.accountId == intent.accountId && current.visible == intent.visible) {
                    return
                }
                generation += 1
                action?.cancel()
                mutableState.value =
                    if (current.accountId == intent.accountId)
                        current.copy(
                            visible = intent.visible,
                            loading = false,
                            selectedRequestKey =
                                current.selectedRequestKey.takeIf { intent.visible },
                        )
                    else PurchaseActivityState(intent.accountId, intent.visible)
                refresh()
            }
            PurchaseActivityIntent.Refresh -> refresh()
            is PurchaseActivityIntent.OpenReceipt -> {
                val current = mutableState.value
                if (!current.visible || current.accountId == null) return
                if (current.entries.none { it.request.requestKey == intent.requestKey }) return
                mutableState.value = current.copy(selectedRequestKey = intent.requestKey)
            }
            PurchaseActivityIntent.CloseReceipt ->
                mutableState.value = mutableState.value.copy(selectedRequestKey = null)
        }
    }

    /**
     * One cancellable read per visit; failures remain visible and never imply a payment outcome.
     */
    private fun refresh() {
        val loadHistory = loadPurchaseHistory ?: return
        val state = mutableState.value
        val accountId = state.accountId ?: return
        if (!state.visible || state.loading) {
            return
        }
        val visit = generation
        mutableState.value = state.copy(loading = true, issue = null)
        action = viewModelScope.launch {
            try {
                val entries = loadHistory(accountId)
                if (visit != generation) {
                    return@launch
                }
                mutableState.value = mutableState.value.copy(loading = false, entries = entries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PurchaseFailure) {
                if (visit != generation) {
                    return@launch
                }
                mutableState.value = mutableState.value.copy(loading = false, issue = failure.issue)
            }
        }
    }
}
