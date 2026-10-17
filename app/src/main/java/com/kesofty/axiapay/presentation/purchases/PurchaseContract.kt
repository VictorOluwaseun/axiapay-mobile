package com.kesofty.axiapay.presentation.purchases

import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.PaymentFormState

/**
 * Only immediate airtime/data entries use the real feature; scheduled/bulk tools remain previews.
 */
val PaymentFormState.purchaseService: PurchaseService?
    get() {
        if (input.scheduled) {
            return null
        }
        return when (route) {
            "Airtime" -> PurchaseService.Airtime
            "Data" -> PurchaseService.Data
            else -> null
        }
    }

/** Select real phone-service sheets without inspecting build flavor in Views. */
val PaymentFormState.isWalletPurchaseEntry: Boolean
    get() = purchaseService != null

/** Explicit live-purchase panels; none of these states represent a demo payment. */
enum class PurchaseScreen {
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
data class PurchaseUiState(
    val enabled: Boolean = false,
    val accountId: String? = null,
    val routeId: String? = null,
    val screen: PurchaseScreen = PurchaseScreen.Hidden,
    val context: PurchaseContext? = null,
    val selectedProductId: String? = null,
    val recipient: String = "",
    val amount: String = "",
    val prepared: PurchaseQuoteInput? = null,
    val inputIssue: PurchaseIssue? = null,
    val review: PurchaseReview? = null,
    val progress: PurchaseProgress? = null,
    val secondsRemaining: Long = 0,
    val isProcessing: Boolean = false,
    val issue: PurchaseIssue? = null,
    val service: PurchaseService = PurchaseService.Airtime,
    val selectedProviderCode: String? = null,
) {
    /** Publish only the selected API identity, never a locally guessed provider/product mapping. */
    val selectedProduct: PurchaseProduct?
        get() = context?.products?.find { it.id == selectedProductId }

    /** Catalogue networks remain explicit, including for ported recipients. */
    val providers: List<String>
        get() = context?.products.orEmpty().map { it.providerCode }.distinct()

    /**
     * Data plans belong to the explicitly selected API network; airtime retains direct selection.
     */
    val visibleProducts: List<PurchaseProduct>
        get() =
            context?.products.orEmpty().filter {
                service == PurchaseService.Airtime || it.providerCode == selectedProviderCode
            }

    /**
     * Do not render a previous account/route during the frame before its binding intent is
     * consumed.
     */
    fun forScope(
        accountId: String?,
        routeId: String?,
        service: PurchaseService = PurchaseService.Airtime,
    ): PurchaseUiState {
        if (this.accountId == accountId && this.routeId == routeId && this.service == service) {
            return this
        }
        return PurchaseUiState(
            enabled,
            accountId,
            routeId,
            if (accountId == null) PurchaseScreen.SignIn else PurchaseScreen.Loading,
            service = service,
        )
    }

    /** Keep phone, amounts and server records out of diagnostics. */
    override fun toString() =
        "PurchaseUiState(screen=$screen, processing=$isProcessing, details=redacted)"
}

/**
 * Every purchase/resume/reconcile action requires an explicit user intent; binding/restoration
 * performs reads only.
 */
sealed interface PurchaseIntent {
    /**
     * Bind the verified account and existing sheet visit; null closes and drops transient personal
     * data.
     */
    data class Bind(
        val accountId: String?,
        val routeId: String?,
        val service: PurchaseService = PurchaseService.Airtime,
    ) : PurchaseIntent

    /** Retry wallet/catalogue/recovery-reference reads without submitting a payment. */
    data object Reload : PurchaseIntent

    /** Changing a data network discards its previously selected plan and amount. */
    data class SelectProvider(val code: String) : PurchaseIntent

    /** Select the API product explicitly, including for ported numbers. */
    data class SelectProduct(val id: String) : PurchaseIntent

    /** Change transient recipient text only. */
    data class EditRecipient(val value: String) : PurchaseIntent

    /** Change transient decimal input only. */
    data class EditAmount(val value: String) : PurchaseIntent

    /** Request immutable server review terms. */
    data object Review : PurchaseIntent

    /** Return to editing; the next review creates a fresh quote only after an explicit tap. */
    data object Edit : PurchaseIntent

    /** Confirm exactly the current quote under its original generated key. */
    data object Confirm : PurchaseIntent

    /** Open the owner's earlier request references. */
    data object History : PurchaseIntent

    /** Explicitly start another purchase while keeping every earlier recovery reference. */
    data object NewPurchase : PurchaseIntent

    /** Read a saved request's progress without executing or reserving. */
    data class CheckSaved(val requestKey: String) : PurchaseIntent

    /** Read the currently displayed request's progress. */
    data object CheckStatus : PurchaseIntent

    /** Explicitly recover a held accepted payment under its existing server identity. */
    data object Reconcile : PurchaseIntent

    /** Resume/retry only the same quote/key after uncertainty or funding. */
    data object RetrySubmission : PurchaseIntent

    /** Acknowledge an observed final receipt and request closing this sheet only. */
    data object Done : PurchaseIntent
}

/** Lifecycle-consumed UI effects carry owner/visit guards; neither effect can submit a payment. */
sealed interface PurchaseEffect {
    /** Refresh display data after a possibly accepted write, even when its response was lost. */
    data class RefreshWallet(val accountId: String) : PurchaseEffect

    /** Close only the originating visit after terminal receipt acknowledgement. */
    data class Close(val accountId: String, val routeId: String, val visit: Long) : PurchaseEffect
}
