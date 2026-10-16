package com.kesofty.axiapay.presentation.airtime

import com.kesofty.axiapay.domain.airtime.*
import com.kesofty.axiapay.presentation.PaymentFormState

/**
 * Only the existing immediate airtime entry switches to the live feature; scheduled/bulk tools
 * retain preview labels.
 */
val PaymentFormState.isAirtimePurchaseEntry: Boolean
    get() = route == "Airtime" && !input.scheduled

/** Explicit live-purchase panels; none of these states represent a demo payment. */
enum class AirtimeScreen {
    Hidden,
    Loading,
    SignIn,
    LoadFailure,
    Form,
    Review,
    History,
    Progress,
}

/**
 * Transient account-owned render state. Recipient, amounts and quote bodies are never saved or
 * logged.
 */
data class AirtimeUiState(
    val enabled: Boolean = false,
    val accountId: String? = null,
    val routeId: String? = null,
    val screen: AirtimeScreen = AirtimeScreen.Hidden,
    val context: AirtimeContext? = null,
    val selectedProductId: String? = null,
    val recipient: String = "",
    val amount: String = "",
    val prepared: AirtimeQuoteInput? = null,
    val inputIssue: AirtimeIssue? = null,
    val review: AirtimeReview? = null,
    val progress: AirtimeProgress? = null,
    val secondsRemaining: Long = 0,
    val isProcessing: Boolean = false,
    val issue: AirtimeIssue? = null,
) {
    /** Publish only the selected API identity, never a locally guessed provider/product mapping. */
    val selectedProduct: AirtimeProduct?
        get() = context?.products?.find { it.id == selectedProductId }

    /**
     * Do not render a previous account/route during the frame before its binding intent is
     * consumed.
     */
    fun forScope(accountId: String?, routeId: String?): AirtimeUiState {
        if (this.accountId == accountId && this.routeId == routeId) {
            return this
        }
        return AirtimeUiState(
            enabled,
            accountId,
            routeId,
            if (accountId == null) AirtimeScreen.SignIn else AirtimeScreen.Loading,
        )
    }

    /** Keep phone, amounts and server records out of diagnostics. */
    override fun toString() =
        "AirtimeUiState(screen=$screen, processing=$isProcessing, details=redacted)"
}

/**
 * Every purchase/resume/reconcile action requires an explicit user intent; binding/restoration
 * performs reads only.
 */
sealed interface AirtimeIntent {
    /**
     * Bind the verified account and existing sheet visit; null closes and drops transient personal
     * data.
     */
    data class Bind(val accountId: String?, val routeId: String?) : AirtimeIntent

    /** Retry wallet/catalogue/recovery-reference reads without submitting a payment. */
    data object Reload : AirtimeIntent

    /** Select the API product explicitly, including for ported numbers. */
    data class SelectProduct(val id: String) : AirtimeIntent

    /** Change transient recipient text only. */
    data class EditRecipient(val value: String) : AirtimeIntent

    /** Change transient decimal input only. */
    data class EditAmount(val value: String) : AirtimeIntent

    /** Request immutable server review terms. */
    data object Review : AirtimeIntent

    /** Return to editing; the next review creates a fresh quote only after an explicit tap. */
    data object Edit : AirtimeIntent

    /** Confirm exactly the current quote under its original generated key. */
    data object Confirm : AirtimeIntent

    /** Open the owner's earlier request references. */
    data object History : AirtimeIntent

    /** Explicitly start another purchase while keeping every earlier recovery reference. */
    data object NewPurchase : AirtimeIntent

    /** Read a saved request's progress without executing or reserving. */
    data class CheckSaved(val requestKey: String) : AirtimeIntent

    /** Read the currently displayed request's progress. */
    data object CheckStatus : AirtimeIntent

    /** Explicitly recover a held accepted payment under its existing server identity. */
    data object Reconcile : AirtimeIntent

    /** Resume/retry only the same quote/key after uncertainty or funding. */
    data object RetrySubmission : AirtimeIntent

    /** Acknowledge an observed final receipt and request closing this sheet only. */
    data object Done : AirtimeIntent
}

/** Lifecycle-consumed UI effects carry owner/visit guards; neither effect can submit a payment. */
sealed interface AirtimeEffect {
    /** Refresh display data after a possibly accepted write, even when its response was lost. */
    data class RefreshWallet(val accountId: String) : AirtimeEffect

    /** Close only the originating visit after terminal receipt acknowledgement. */
    data class Close(val accountId: String, val routeId: String, val visit: Long) : AirtimeEffect
}
