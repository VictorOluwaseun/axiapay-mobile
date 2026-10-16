package com.kesofty.axiapay.presentation.telecom

import com.kesofty.axiapay.domain.telecom.*
import com.kesofty.axiapay.presentation.PaymentFormState

/**
 * Only immediate airtime/data entries use the real feature; scheduled/bulk tools remain previews.
 */
val PaymentFormState.telecomService: TelecomService?
    get() {
        if (input.scheduled) {
            return null
        }
        return when (route) {
            "Airtime" -> TelecomService.Airtime
            "Data" -> TelecomService.Data
            else -> null
        }
    }

/** Select real phone-service sheets without inspecting build flavor in Views. */
val PaymentFormState.isTelecomPurchaseEntry: Boolean
    get() = telecomService != null

/** Explicit live-purchase panels; none of these states represent a demo payment. */
enum class TelecomScreen {
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
data class TelecomUiState(
    val enabled: Boolean = false,
    val accountId: String? = null,
    val routeId: String? = null,
    val screen: TelecomScreen = TelecomScreen.Hidden,
    val context: TelecomContext? = null,
    val selectedProductId: String? = null,
    val recipient: String = "",
    val amount: String = "",
    val prepared: TelecomQuoteInput? = null,
    val inputIssue: TelecomIssue? = null,
    val review: TelecomReview? = null,
    val progress: TelecomProgress? = null,
    val secondsRemaining: Long = 0,
    val isProcessing: Boolean = false,
    val issue: TelecomIssue? = null,
    val service: TelecomService = TelecomService.Airtime,
    val selectedNetworkCode: String? = null,
) {
    /** Publish only the selected API identity, never a locally guessed provider/product mapping. */
    val selectedProduct: TelecomProduct?
        get() = context?.products?.find { it.id == selectedProductId }

    /** Catalogue networks remain explicit, including for ported recipients. */
    val networks: List<String>
        get() = context?.products.orEmpty().map { it.networkCode }.distinct()

    /**
     * Data plans belong to the explicitly selected API network; airtime retains direct selection.
     */
    val visibleProducts: List<TelecomProduct>
        get() =
            context?.products.orEmpty().filter {
                service == TelecomService.Airtime || it.networkCode == selectedNetworkCode
            }

    /**
     * Do not render a previous account/route during the frame before its binding intent is
     * consumed.
     */
    fun forScope(
        accountId: String?,
        routeId: String?,
        service: TelecomService = TelecomService.Airtime,
    ): TelecomUiState {
        if (this.accountId == accountId && this.routeId == routeId && this.service == service) {
            return this
        }
        return TelecomUiState(
            enabled,
            accountId,
            routeId,
            if (accountId == null) TelecomScreen.SignIn else TelecomScreen.Loading,
            service = service,
        )
    }

    /** Keep phone, amounts and server records out of diagnostics. */
    override fun toString() =
        "TelecomUiState(screen=$screen, processing=$isProcessing, details=redacted)"
}

/**
 * Every purchase/resume/reconcile action requires an explicit user intent; binding/restoration
 * performs reads only.
 */
sealed interface TelecomIntent {
    /**
     * Bind the verified account and existing sheet visit; null closes and drops transient personal
     * data.
     */
    data class Bind(
        val accountId: String?,
        val routeId: String?,
        val service: TelecomService = TelecomService.Airtime,
    ) : TelecomIntent

    /** Retry wallet/catalogue/recovery-reference reads without submitting a payment. */
    data object Reload : TelecomIntent

    /** Changing a data network discards its previously selected plan and amount. */
    data class SelectNetwork(val code: String) : TelecomIntent

    /** Select the API product explicitly, including for ported numbers. */
    data class SelectProduct(val id: String) : TelecomIntent

    /** Change transient recipient text only. */
    data class EditRecipient(val value: String) : TelecomIntent

    /** Change transient decimal input only. */
    data class EditAmount(val value: String) : TelecomIntent

    /** Request immutable server review terms. */
    data object Review : TelecomIntent

    /** Return to editing; the next review creates a fresh quote only after an explicit tap. */
    data object Edit : TelecomIntent

    /** Confirm exactly the current quote under its original generated key. */
    data object Confirm : TelecomIntent

    /** Open the owner's earlier request references. */
    data object History : TelecomIntent

    /** Explicitly start another purchase while keeping every earlier recovery reference. */
    data object NewPurchase : TelecomIntent

    /** Read a saved request's progress without executing or reserving. */
    data class CheckSaved(val requestKey: String) : TelecomIntent

    /** Read the currently displayed request's progress. */
    data object CheckStatus : TelecomIntent

    /** Explicitly recover a held accepted payment under its existing server identity. */
    data object Reconcile : TelecomIntent

    /** Resume/retry only the same quote/key after uncertainty or funding. */
    data object RetrySubmission : TelecomIntent

    /** Acknowledge an observed final receipt and request closing this sheet only. */
    data object Done : TelecomIntent
}

/** Lifecycle-consumed UI effects carry owner/visit guards; neither effect can submit a payment. */
sealed interface TelecomEffect {
    /** Refresh display data after a possibly accepted write, even when its response was lost. */
    data class RefreshWallet(val accountId: String) : TelecomEffect

    /** Close only the originating visit after terminal receipt acknowledgement. */
    data class Close(val accountId: String, val routeId: String, val visit: Long) : TelecomEffect
}
