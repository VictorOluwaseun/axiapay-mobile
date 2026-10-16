package com.kesofty.axiapay.presentation.telecom

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.telecom.PrepareTelecom
import com.kesofty.axiapay.application.telecom.TelecomPurchases
import com.kesofty.axiapay.domain.telecom.*
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
 * Own the live airtime/data MVI flow independently of the demo ledger. Optional composition leaves
 * the original demo path intact. Visit/account changes cancel UI work; durable IDs allow explicit
 * recovery without replaying a command on recreation, restoration or reconnect.
 */
@HiltViewModel
class TelecomViewModel
@Inject
constructor(
    feature: Optional<TelecomPurchases>,
    private val prepare: PrepareTelecom,
    private val clock: Clock,
) : ViewModel() {
    private val purchases = feature.orElse(null)
    private val mutableState = MutableStateFlow(TelecomUiState(enabled = purchases != null))
    val uiState = mutableState.asStateFlow()
    private val effectChannel = Channel<TelecomEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var action: Job? = null
    private var ticker: Job? = null
    private var visit = 0L

    /** Keep one operation per visit; Views emit intents and never mutate the render snapshot. */
    @MainThread
    fun accept(intent: TelecomIntent) {
        if (purchases == null) {
            return
        }
        if (intent is TelecomIntent.Bind) {
            bind(intent)
            return
        }
        val state = mutableState.value
        if (state.routeId == null || state.accountId == null || state.isProcessing) {
            return
        }
        when (intent) {
            TelecomIntent.Reload -> load()
            is TelecomIntent.SelectNetwork -> selectNetwork(intent.code)
            is TelecomIntent.SelectProduct -> selectProduct(intent.id)
            is TelecomIntent.EditRecipient -> editForm { copy(recipient = intent.value.take(40)) }
            is TelecomIntent.EditAmount -> editForm { copy(amount = intent.value.take(20)) }
            TelecomIntent.Review -> review()
            TelecomIntent.Edit -> {
                ticker?.cancel()
                mutableState.value =
                    state.copy(screen = TelecomScreen.Form, review = null, issue = null)
            }
            TelecomIntent.Confirm -> confirm()
            TelecomIntent.History ->
                mutableState.value = state.copy(screen = TelecomScreen.History, issue = null)
            TelecomIntent.NewPurchase -> newPurchase()
            is TelecomIntent.CheckSaved -> {
                val request =
                    state.context?.requests?.find { it.requestKey == intent.requestKey } ?: return
                progress(refreshWallet = true) { purchases.recover(state.accountId, request) }
            }
            TelecomIntent.CheckStatus -> {
                val request = state.progress?.request ?: return
                progress(refreshWallet = true) { purchases.recover(state.accountId, request) }
            }
            TelecomIntent.Reconcile -> {
                val current = state.progress ?: return
                if (
                    current.payment?.status !in
                        setOf(TelecomPaymentStatus.Pending, TelecomPaymentStatus.Reserved)
                ) {
                    return
                }
                progress(refreshWallet = true) {
                    purchases.reconcile(state.accountId, current.request)
                }
            }
            TelecomIntent.RetrySubmission -> {
                val current = state.progress ?: return
                if (
                    current.payment?.isFinal == true ||
                        current.payment?.status in
                            setOf(TelecomPaymentStatus.Pending, TelecomPaymentStatus.Reserved)
                ) {
                    return
                }
                progress(refreshWallet = true) {
                    purchases.retrySubmission(state.accountId, current.request)
                }
            }
            TelecomIntent.Done -> acknowledge()
            is TelecomIntent.Bind -> Unit
        }
    }

    /** Reject queued closure after the same retained navigation ID has been reopened. */
    @MainThread
    fun isCurrentClose(effect: TelecomEffect.Close): Boolean =
        effect.visit == visit &&
            effect.accountId == mutableState.value.accountId &&
            effect.routeId == mutableState.value.routeId

    /**
     * Drop transient personal data immediately when an account or sheet changes; new bindings read
     * only.
     */
    private fun bind(intent: TelecomIntent.Bind) {
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
                intent.routeId == null -> TelecomScreen.Hidden
                intent.accountId == null -> TelecomScreen.SignIn
                else -> TelecomScreen.Loading
            }
        mutableState.value =
            TelecomUiState(true, intent.accountId, intent.routeId, screen, service = intent.service)
        if (screen == TelecomScreen.Loading) {
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
    private fun selectNetwork(code: String) {
        val state = mutableState.value
        if (
            state.service != TelecomService.Data ||
                code !in state.networks ||
                state.selectedNetworkCode == code
        ) {
            return
        }
        editForm { copy(selectedNetworkCode = code, selectedProductId = null, amount = "") }
    }

    /** Only a visible catalogue product from this visit can be selected. */
    private fun selectProduct(id: String) {
        if (mutableState.value.visibleProducts.none { it.id == id }) {
            return
        }
        editForm { copy(selectedProductId = id) }
    }

    /**
     * Prepare changed input once; selected API constraints provide feedback while the server
     * remains authoritative.
     */
    private fun editForm(update: TelecomUiState.() -> TelecomUiState) {
        val state = mutableState.value
        if (state.screen != TelecomScreen.Form) {
            return
        }
        val changed = state.update().copy(issue = null)
        val check = prepare(changed.selectedProduct, changed.recipient, changed.amount)
        mutableState.value = changed.copy(prepared = check.input, inputIssue = check.issue)
    }

    /** Request a quote only after valid boundary preparation, never as a rendering side effect. */
    private fun review() {
        val state = mutableState.value
        val input = state.prepared ?: return
        val owner = state.accountId ?: return
        val workflow = purchases ?: return
        if (state.screen != TelecomScreen.Form) {
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
            state.screen != TelecomScreen.Review ||
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
    private fun progress(refreshWallet: Boolean = false, operation: suspend () -> TelecomProgress) {
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
            TelecomUiState(
                true,
                state.accountId,
                state.routeId,
                TelecomScreen.Form,
                state.context,
                service = state.service,
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
        if (progress.payment?.isFinal != true) {
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
        data class Loaded(val context: TelecomContext) : ActionResult

        /** Immutable server review ready for explicit consent. */
        data class Reviewed(val review: TelecomReview) : ActionResult

        /** Correlated server progress or an honest uncertain result. */
        data class Observed(val progress: TelecomProgress) : ActionResult

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
                                TelecomScreen.History
                            else TelecomScreen.Form,
                    )
            is ActionResult.Reviewed -> {
                mutableState.value =
                    state.copy(
                        screen = TelecomScreen.Review,
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
                        screen = TelecomScreen.Progress,
                        progress = progress,
                        review = null,
                        context =
                            state.context?.copy(requests = Collections.unmodifiableList(records)),
                    )
            }
            ActionResult.Acknowledged ->
                effectChannel.trySend(
                    TelecomEffect.Close(
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
    private fun retainedQuote(state: TelecomUiState, request: TelecomRequest): TelecomQuote? {
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
                val issue = (failure as? TelecomFailure)?.issue ?: TelecomIssue.Unavailable
                val invalidSession =
                    issue in setOf(TelecomIssue.SignInRequired, TelecomIssue.SessionChanged)
                refreshAccountWallet = refreshAccountWallet || invalidSession
                val screen =
                    when {
                        invalidSession -> TelecomScreen.SignIn
                        mutableState.value.context == null -> TelecomScreen.LoadFailure
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
            effectChannel.trySend(TelecomEffect.RefreshWallet(owner))
        }
    }

    /** Advance display expiry only; time passing never requotes, submits or reconciles. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (mutableState.value.screen == TelecomScreen.Review) {
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
    private fun remaining(quote: TelecomQuote): Long =
        Duration.between(clock.instant(), quote.expiresAt).seconds.coerceAtLeast(0)
}
