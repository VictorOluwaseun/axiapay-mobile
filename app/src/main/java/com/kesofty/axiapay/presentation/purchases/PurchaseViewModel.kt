package com.kesofty.axiapay.presentation.purchases

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.purchases.PreparePurchase
import com.kesofty.axiapay.application.purchases.WalletPurchases
import com.kesofty.axiapay.application.purchases.hasMeterSyntax
import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.allows
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Own the live wallet purchase MVI flow independently of the demo ledger. Optional composition
 * leaves the original demo path intact. Visit/account changes cancel UI work; durable IDs allow
 * explicit recovery without replaying a command on recreation, restoration or reconnect.
 */
@HiltViewModel
class PurchaseViewModel
@Inject
constructor(
    feature: Optional<WalletPurchases>,
    private val prepare: PreparePurchase,
    private val clock: Clock,
) : ViewModel() {
    private val purchases = feature.orElse(null)
    private var features = com.kesofty.axiapay.domain.config.PaymentFeatures.Disabled
    private val mutableState = MutableStateFlow(PurchaseUiState(enabled = purchases != null))
    val uiState = mutableState.asStateFlow()
    private val effectChannel = Channel<PurchaseEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var action: Job? = null
    private var ticker: Job? = null
    private var visit = 0L

    /** Keep one operation per visit; Views emit intents and never mutate the render snapshot. */
    @MainThread
    fun accept(intent: PurchaseIntent) {
        if (intent is PurchaseIntent.UpdateFeatures) {
            features = intent.features
            mutableState.value =
                mutableState.value.copy(
                    featureAvailable = features.allows(mutableState.value.service),
                )
            return
        }
        if (purchases == null) {
            return
        }
        if (intent is PurchaseIntent.Bind) {
            bind(intent)
            return
        }
        val state = mutableState.value
        if (state.routeId == null || state.accountId == null || state.isProcessing) {
            return
        }
        if (
            !state.featureAvailable &&
                intent in
                    setOf(
                        PurchaseIntent.Review,
                        PurchaseIntent.Confirm,
                        PurchaseIntent.VerifyMeter,
                        PurchaseIntent.NewPurchase,
                    )
        )
            return
        when (intent) {
            is PurchaseIntent.UpdateFeatures -> Unit
            PurchaseIntent.Reload -> load()
            is PurchaseIntent.SelectProvider -> selectProvider(intent.code)
            is PurchaseIntent.SelectProduct -> selectProduct(intent.id)
            is PurchaseIntent.EditRecipient ->
                editForm {
                    copy(
                        recipient =
                            intent.value.take(
                                if (service == PurchaseService.Electricity) 100 else 40,
                            ),
                        verifiedMeter = null,
                    )
                }
            is PurchaseIntent.SelectMeterType -> selectMeterType(intent.value)
            is PurchaseIntent.EditContactPhone ->
                editForm { copy(contactPhoneNumber = intent.value.take(40)) }
            PurchaseIntent.VerifyMeter -> verifyMeter()
            is PurchaseIntent.EditAmount -> editForm { copy(amount = intent.value.take(20)) }
            PurchaseIntent.Review -> review()
            PurchaseIntent.Edit -> {
                ticker?.cancel()
                mutableState.value =
                    state.copy(screen = PurchaseScreen.Form, review = null, issue = null)
            }
            PurchaseIntent.Confirm -> confirm()
            PurchaseIntent.History ->
                mutableState.value = state.copy(screen = PurchaseScreen.History, issue = null)
            PurchaseIntent.NewPurchase -> newPurchase()
            is PurchaseIntent.CheckSaved -> {
                val request =
                    state.context?.requests?.find { it.requestKey == intent.requestKey } ?: return
                progress(refreshWallet = true) { purchases.recover(state.accountId, request) }
            }
            PurchaseIntent.CheckStatus -> {
                val request = state.progress?.request ?: return
                progress(refreshWallet = true) { purchases.recover(state.accountId, request) }
            }
            PurchaseIntent.Reconcile -> {
                val current = state.progress ?: return
                if (current.payment?.canReconcile != true) {
                    return
                }
                progress(refreshWallet = true) {
                    purchases.reconcile(state.accountId, current.request)
                }
            }
            PurchaseIntent.RetrySubmission -> {
                val current = state.progress ?: return
                if (
                    current.payment?.isFinal == true ||
                        current.payment?.status in
                            setOf(PurchasePaymentStatus.Pending, PurchasePaymentStatus.Reserved)
                ) {
                    return
                }
                progress(refreshWallet = true) {
                    purchases.retrySubmission(state.accountId, current.request)
                }
            }
            PurchaseIntent.Done -> acknowledge()
            is PurchaseIntent.Bind -> Unit
        }
    }

    /** Reject queued closure after the same retained navigation ID has been reopened. */
    @MainThread
    fun isCurrentClose(effect: PurchaseEffect.Close): Boolean =
        effect.visit == visit &&
            effect.accountId == mutableState.value.accountId &&
            effect.routeId == mutableState.value.routeId

    /**
     * Drop transient personal data immediately when an account or sheet changes; new bindings read
     * only.
     */
    private fun bind(intent: PurchaseIntent.Bind) {
        val state = mutableState.value
        if (
            state.accountId == intent.accountId &&
                state.routeId == intent.routeId &&
                state.service == intent.service
        ) {
            return
        }
        visit += 1
        action?.cancel()
        ticker?.cancel()
        val screen =
            when {
                intent.routeId == null -> PurchaseScreen.Hidden
                intent.accountId == null -> PurchaseScreen.SignIn
                else -> PurchaseScreen.Loading
            }
        mutableState.value =
            PurchaseUiState(
                true,
                intent.accountId,
                intent.routeId,
                screen,
                service = intent.service,
                featureAvailable = features.allows(intent.service),
            )
        if (screen == PurchaseScreen.Loading) {
            load()
        }
    }

    /** Loading/restoration discovers existing references instead of submitting them. */
    private fun load() {
        val owner = mutableState.value.accountId ?: return
        val workflow = purchases ?: return
        val service = mutableState.value.service
        execute { ActionResult.Loaded(workflow.load(owner, service)) }
    }

    /** Data network selection clears the previous plan so a hidden plan cannot be purchased. */
    private fun selectProvider(code: String) {
        val state = mutableState.value
        if (
            state.service == PurchaseService.Airtime ||
                code !in state.providers ||
                state.selectedProviderCode == code
        ) {
            return
        }
        editForm {
            copy(
                selectedProviderCode = code,
                selectedProductId = null,
                amount = "",
                verifiedMeter = null,
            )
        }
    }

    /** Only a visible catalogue product from this visit can be selected. */
    private fun selectProduct(id: String) {
        if (mutableState.value.visibleProducts.none { it.id == id }) {
            return
        }
        if (mutableState.value.selectedProductId == id) {
            return
        }
        editForm { copy(selectedProductId = id, verifiedMeter = null) }
    }

    /**
     * Prepare changed input once; selected API constraints provide feedback while the server
     * remains authoritative.
     */
    private fun editForm(update: PurchaseUiState.() -> PurchaseUiState) {
        val state = mutableState.value
        if (state.screen != PurchaseScreen.Form) {
            return
        }
        val changed = state.update().copy(issue = null)
        val check =
            prepare(
                changed.selectedProduct,
                changed.recipient,
                changed.amount,
                changed.verifiedMeter,
                changed.contactPhoneNumber,
            )
        mutableState.value = changed.copy(prepared = check.input, inputIssue = check.issue)
    }

    /**
     * Meter modes are product constraints; changing mode discards prior verification and quote
     * input.
     */
    private fun selectMeterType(value: ElectricityMeterType) {
        if (
            mutableState.value.service != PurchaseService.Electricity ||
                mutableState.value.selectedMeterType == value
        ) {
            return
        }
        editForm { copy(selectedMeterType = value, selectedProductId = null, verifiedMeter = null) }
    }

    /**
     * Verify exact prepared meter text only after a user tap; late results retain normal visit
     * guards.
     */
    private fun verifyMeter() {
        val state = mutableState.value
        val product = state.selectedProduct ?: return
        val owner = state.accountId ?: return
        val workflow = purchases ?: return
        if (
            state.screen != PurchaseScreen.Form ||
                state.service != PurchaseService.Electricity ||
                !hasMeterSyntax(state.recipient)
        ) {
            return
        }
        editForm { copy(verifiedMeter = null) }
        execute {
            ActionResult.MeterVerified(workflow.verifyMeter(owner, product, state.recipient))
        }
    }

    /** Request a quote only after valid boundary preparation, never as a rendering side effect. */
    private fun review() {
        val state = mutableState.value
        val input = state.prepared ?: return
        val owner = state.accountId ?: return
        val workflow = purchases ?: return
        if (state.screen != PurchaseScreen.Form) {
            return
        }
        execute { ActionResult.Reviewed(workflow.review(owner, input)) }
    }

    /**
     * Duplicate taps cannot create another identity or POST; the workflow saves the reviewed key
     * before IO.
     */
    private fun confirm() {
        val state = mutableState.value
        val reviewed = state.review ?: return
        val owner = state.accountId ?: return
        val workflow = purchases ?: return
        if (
            state.screen != PurchaseScreen.Review ||
                state.secondsRemaining == 0L ||
                !reviewed.quote.isSubmissionAvailable
        ) {
            return
        }
        progress(refreshWallet = true) { workflow.submit(owner, reviewed) }
    }

    /**
     * Publish observed or explicitly unconfirmed progress; errors never invent successful or failed
     * delivery.
     */
    private fun progress(
        refreshWallet: Boolean = false,
        operation: suspend () -> PurchaseProgress,
    ) {
        execute(refreshWallet) { ActionResult.Observed(operation()) }
    }

    /** An explicit new instruction keeps all earlier uncertain identities available in history. */
    private fun newPurchase() {
        val state = mutableState.value
        if (state.context == null) {
            load()
            return
        }
        ticker?.cancel()
        mutableState.value =
            PurchaseUiState(
                true,
                state.accountId,
                state.routeId,
                PurchaseScreen.Form,
                state.context,
                service = state.service,
                featureAvailable = features.allows(state.service),
            )
    }

    /**
     * Only an observed final receipt can clear its local reference; a late effect cannot close a
     * newer visit.
     */
    private fun acknowledge() {
        val state = mutableState.value
        val progress = state.progress ?: return
        val workflow = purchases ?: return
        if (progress.payment?.canAcknowledge != true) {
            return
        }
        execute {
            workflow.acknowledge(progress)
            ActionResult.Acknowledged
        }
    }

    /** IO produces typed results; only the owning visit may reduce them into state/effects. */
    private sealed interface ActionResult {
        /** Fresh owned display data and saved receipt/recovery references. */
        data class Loaded(val context: PurchaseContext) : ActionResult

        /** Owner-bound verified meter for the unchanged form. */
        data class MeterVerified(val value: VerifiedMeter) : ActionResult

        /** Immutable server review ready for explicit consent. */
        data class Reviewed(val review: PurchaseReview) : ActionResult

        /** Correlated server progress or an honest uncertain result. */
        data class Observed(val progress: PurchaseProgress) : ActionResult

        /** Local terminal receipt acknowledgement completed. */
        data object Acknowledged : ActionResult
    }

    /**
     * Publish IO only after the visit guard, preserving immutable snapshots and earlier request
     * references.
     */
    private fun applyResult(result: ActionResult) {
        val state = mutableState.value
        when (result) {
            is ActionResult.Loaded ->
                mutableState.value =
                    state.copy(
                        context = result.context,
                        issue = result.context.issue,
                        screen =
                            if (
                                result.context.requests.any { !it.acknowledged } ||
                                    result.context.wallet == null
                            )
                                PurchaseScreen.History
                            else PurchaseScreen.Form,
                    )
            is ActionResult.MeterVerified -> editForm { copy(verifiedMeter = result.value) }
            is ActionResult.Reviewed -> {
                mutableState.value =
                    state.copy(
                        screen = PurchaseScreen.Review,
                        review = result.review,
                        secondsRemaining = remaining(result.review.quote),
                    )
                startTicker()
            }
            is ActionResult.Observed -> {
                val quote = result.progress.quote ?: retainedQuote(state, result.progress.request)
                val progress = result.progress.copy(quote = quote)
                ticker?.cancel()
                val records =
                    state.context?.requests.orEmpty().filterNot {
                        it.requestKey == progress.request.requestKey
                    } + progress.request
                mutableState.value =
                    state.copy(
                        screen = PurchaseScreen.Progress,
                        progress = progress,
                        review = null,
                        context =
                            state.context?.copy(requests = Collections.unmodifiableList(records)),
                    )
            }
            ActionResult.Acknowledged ->
                effectChannel.trySend(
                    PurchaseEffect.Close(
                        requireNotNull(state.accountId),
                        requireNotNull(state.routeId),
                        visit,
                    ),
                )
        }
    }

    /**
     * A quote can survive uncertainty only for the exact saved/reviewed request that supplied it.
     */
    private fun retainedQuote(state: PurchaseUiState, request: PurchaseRequest): PurchaseQuote? {
        val progressQuote = state.progress?.takeIf { it.request == request }?.quote
        val reviewQuote = state.review?.takeIf { it.request == request }?.quote
        return (progressQuote ?: reviewQuote)?.takeIf {
            it.id == request.quoteId && it.service == request.service
        }
    }

    /**
     * Own one cancellable action and reject late completion after newer navigation or account
     * binding.
     */
    private fun execute(refreshWallet: Boolean = false, operation: suspend () -> ActionResult) {
        val origin = visit
        val owner = mutableState.value.accountId ?: return
        if (mutableState.value.isProcessing) {
            return
        }
        mutableState.value = mutableState.value.copy(isProcessing = true, issue = null)
        action = viewModelScope.launch {
            var refreshAccountWallet = refreshWallet
            try {
                val result = operation()
                if (origin != visit) {
                    return@launch
                }
                applyResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (origin != visit) {
                    return@launch
                }
                val issue = (failure as? PurchaseFailure)?.issue ?: PurchaseIssue.Unavailable
                val invalidSession =
                    issue in setOf(PurchaseIssue.SignInRequired, PurchaseIssue.SessionChanged)
                refreshAccountWallet = refreshAccountWallet || invalidSession
                val screen =
                    when {
                        invalidSession -> PurchaseScreen.SignIn
                        mutableState.value.context == null -> PurchaseScreen.LoadFailure
                        else -> mutableState.value.screen
                    }
                mutableState.value = mutableState.value.copy(issue = issue, screen = screen)
            } finally {
                finishAction(origin, owner, refreshAccountWallet)
            }
        }
    }

    /**
     * End processing only for the owning visit; refresh effects perform reads and carry the
     * verified account guard.
     */
    private fun finishAction(origin: Long, owner: String, refreshWallet: Boolean) {
        if (origin == visit) {
            mutableState.value = mutableState.value.copy(isProcessing = false)
        }
        if (refreshWallet) {
            effectChannel.trySend(PurchaseEffect.RefreshWallet(owner))
        }
    }

    /** Advance display expiry only; time passing never requotes, submits or reconciles. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (mutableState.value.screen == PurchaseScreen.Review) {
                val quote = mutableState.value.review?.quote ?: return@launch
                mutableState.value = mutableState.value.copy(secondsRemaining = remaining(quote))
                delay(1000)
            }
        }
    }

    /**
     * Conservatively disable confirmation during the last fractional second; API expiry remains
     * authoritative.
     */
    private fun remaining(quote: PurchaseQuote): Long =
        Duration.between(clock.instant(), quote.expiresAt).seconds.coerceAtLeast(0)
}
