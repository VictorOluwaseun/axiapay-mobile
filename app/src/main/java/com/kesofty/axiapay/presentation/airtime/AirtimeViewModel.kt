package com.kesofty.axiapay.presentation.airtime

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.airtime.AirtimePurchases
import com.kesofty.axiapay.application.airtime.PrepareAirtime
import com.kesofty.axiapay.domain.airtime.*
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
 * Own the live single-airtime MVI flow independently of the demo ledger. Optional composition
 * leaves the original demo path intact. Visit/account changes cancel UI work; durable IDs allow
 * explicit recovery without replaying a command on recreation, restoration or reconnect.
 */
@HiltViewModel
class AirtimeViewModel
@Inject
constructor(
    feature: Optional<AirtimePurchases>,
    private val prepare: PrepareAirtime,
    private val clock: Clock,
) : ViewModel() {
    private val purchases = feature.orElse(null)
    private val mutableState = MutableStateFlow(AirtimeUiState(enabled = purchases != null))
    val uiState = mutableState.asStateFlow()
    private val effectChannel = Channel<AirtimeEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var action: Job? = null
    private var ticker: Job? = null
    private var visit = 0L

    /** Keep one operation per visit; Views emit intents and never mutate the render snapshot. */
    @MainThread
    fun accept(intent: AirtimeIntent) {
        if (purchases == null) {
            return
        }
        if (intent is AirtimeIntent.Bind) {
            bind(intent)
            return
        }
        val state = mutableState.value
        if (state.routeId == null || state.accountId == null || state.isProcessing) {
            return
        }
        when (intent) {
            AirtimeIntent.Reload -> load()
            is AirtimeIntent.SelectProduct -> editForm { copy(selectedProductId = intent.id) }
            is AirtimeIntent.EditRecipient -> editForm { copy(recipient = intent.value.take(40)) }
            is AirtimeIntent.EditAmount -> editForm { copy(amount = intent.value.take(20)) }
            AirtimeIntent.Review -> review()
            AirtimeIntent.Edit -> {
                ticker?.cancel()
                mutableState.value =
                    state.copy(screen = AirtimeScreen.Form, review = null, issue = null)
            }
            AirtimeIntent.Confirm -> confirm()
            AirtimeIntent.History ->
                mutableState.value = state.copy(screen = AirtimeScreen.History, issue = null)
            AirtimeIntent.NewPurchase -> newPurchase()
            is AirtimeIntent.CheckSaved -> {
                val request =
                    state.context?.requests?.find { it.requestKey == intent.requestKey } ?: return
                progress(refreshWallet = true) { purchases.recover(state.accountId, request) }
            }
            AirtimeIntent.CheckStatus -> {
                val request = state.progress?.request ?: return
                progress(refreshWallet = true) { purchases.recover(state.accountId, request) }
            }
            AirtimeIntent.Reconcile -> {
                val current = state.progress ?: return
                if (
                    current.payment?.status !in
                        setOf(AirtimePaymentStatus.Pending, AirtimePaymentStatus.Reserved)
                ) {
                    return
                }
                progress(refreshWallet = true) {
                    purchases.reconcile(state.accountId, current.request)
                }
            }
            AirtimeIntent.RetrySubmission -> {
                val current = state.progress ?: return
                if (
                    current.payment?.isFinal == true ||
                        current.payment?.status in
                            setOf(AirtimePaymentStatus.Pending, AirtimePaymentStatus.Reserved)
                ) {
                    return
                }
                progress(refreshWallet = true) {
                    purchases.retrySubmission(state.accountId, current.request)
                }
            }
            AirtimeIntent.Done -> acknowledge()
            is AirtimeIntent.Bind -> Unit
        }
    }

    /** Reject queued closure after the same retained navigation ID has been reopened. */
    @MainThread
    fun isCurrentClose(effect: AirtimeEffect.Close): Boolean =
        effect.visit == visit &&
            effect.accountId == mutableState.value.accountId &&
            effect.routeId == mutableState.value.routeId

    /**
     * Drop transient personal data immediately when an account or sheet changes; new bindings read
     * only.
     */
    private fun bind(intent: AirtimeIntent.Bind) {
        val state = mutableState.value
        if (state.accountId == intent.accountId && state.routeId == intent.routeId) {
            return
        }
        visit += 1
        action?.cancel()
        ticker?.cancel()
        val screen =
            when {
                intent.routeId == null -> AirtimeScreen.Hidden
                intent.accountId == null -> AirtimeScreen.SignIn
                else -> AirtimeScreen.Loading
            }
        mutableState.value = AirtimeUiState(true, intent.accountId, intent.routeId, screen)
        if (screen == AirtimeScreen.Loading) {
            load()
        }
    }

    /** Loading/restoration discovers existing references instead of submitting them. */
    private fun load() {
        val owner = mutableState.value.accountId ?: return
        val workflow = purchases ?: return
        execute { ActionResult.Loaded(workflow.load(owner)) }
    }

    /**
     * Prepare changed input once; selected API constraints provide feedback while the server
     * remains authoritative.
     */
    private fun editForm(update: AirtimeUiState.() -> AirtimeUiState) {
        val state = mutableState.value
        if (state.screen != AirtimeScreen.Form) {
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
        if (state.screen != AirtimeScreen.Form) {
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
            state.screen != AirtimeScreen.Review ||
                state.secondsRemaining == 0L ||
                !reviewed.quote.isSubmissionAvailable
        ) {
            return
        }
        ticker?.cancel()
        progress(refreshWallet = true) { workflow.submit(owner, reviewed) }
    }

    /**
     * Publish observed or explicitly unconfirmed progress; errors never invent successful or failed
     * delivery.
     */
    private fun progress(refreshWallet: Boolean = false, operation: suspend () -> AirtimeProgress) {
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
            AirtimeUiState(true, state.accountId, state.routeId, AirtimeScreen.Form, state.context)
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
        data class Loaded(val context: AirtimeContext) : ActionResult

        /** Immutable server review ready for explicit consent. */
        data class Reviewed(val review: AirtimeReview) : ActionResult

        /** Correlated server progress or an honest uncertain result. */
        data class Observed(val progress: AirtimeProgress) : ActionResult

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
                                AirtimeScreen.History
                            else AirtimeScreen.Form,
                    )
            is ActionResult.Reviewed -> {
                mutableState.value =
                    state.copy(
                        screen = AirtimeScreen.Review,
                        review = result.review,
                        secondsRemaining = remaining(result.review.quote),
                    )
                startTicker()
            }
            is ActionResult.Observed -> {
                val quote = result.progress.quote ?: state.progress?.quote ?: state.review?.quote
                val progress = result.progress.copy(quote = quote)
                val records =
                    state.context?.requests.orEmpty().filterNot {
                        it.requestKey == progress.request.requestKey
                    } + progress.request
                mutableState.value =
                    state.copy(
                        screen = AirtimeScreen.Progress,
                        progress = progress,
                        review = null,
                        context =
                            state.context?.copy(requests = Collections.unmodifiableList(records)),
                    )
            }
            ActionResult.Acknowledged ->
                effectChannel.trySend(
                    AirtimeEffect.Close(
                        requireNotNull(state.accountId),
                        requireNotNull(state.routeId),
                        visit,
                    ),
                )
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
                val issue = (failure as? AirtimeFailure)?.issue ?: AirtimeIssue.Unavailable
                val invalidSession =
                    issue in setOf(AirtimeIssue.SignInRequired, AirtimeIssue.SessionChanged)
                refreshAccountWallet = refreshAccountWallet || invalidSession
                val screen =
                    when {
                        invalidSession -> AirtimeScreen.SignIn
                        mutableState.value.context == null -> AirtimeScreen.LoadFailure
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
            effectChannel.trySend(AirtimeEffect.RefreshWallet(owner))
        }
    }

    /** Advance display expiry only; time passing never requotes, submits or reconciles. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (mutableState.value.screen == AirtimeScreen.Review) {
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
    private fun remaining(quote: AirtimeQuote): Long =
        Duration.between(clock.instant(), quote.expiresAt).seconds.coerceAtLeast(0)
}
