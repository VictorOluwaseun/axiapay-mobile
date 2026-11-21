package com.kesofty.axiapay.wallet.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.wallet.application.FundingUseCases
import com.kesofty.axiapay.wallet.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Activity store key used by earlier releases to restore the funding request and retry key. */
internal const val FUNDING_VIEW_MODEL_KEY =
    "androidx.lifecycle.ViewModelProvider.DefaultKey:com.kesofty.axiapay.presentation.funding.FundingViewModel"

/**
 * One owner for immutable form/progress state; credentials and provider evidence never enter
 * SavedStateHandle.
 */
data class FundingUiState(
    val enabled: Boolean = false,
    val accountId: String? = null,
    val routeId: String? = null,
    val context: FundingContext? = null,
    val request: FundingRequest? = null,
    val amount: String = "",
    val email: String = "",
    val optionId: String? = null,
    val reference: String = "",
    val busy: Boolean = false,
    val issue: FundingIssue? = null,
    val submissionUncertain: Boolean = false,
    val offset: Int = 0,
)

/** Explicit customer actions; restoration and navigation only read progress. */
sealed interface FundingIntent {
    data class Bind(val accountId: String?, val routeId: String?) : FundingIntent

    data class Amount(val value: String) : FundingIntent

    data class Email(val value: String) : FundingIntent

    data class Option(val id: String) : FundingIntent

    data class Reference(val value: String) : FundingIntent

    data class Select(val id: String) : FundingIntent

    data class Page(val offset: Int) : FundingIntent

    data object Create : FundingIntent

    data object SubmitReference : FundingIntent

    data object Refresh : FundingIntent

    data object NewRequest : FundingIntent

    data object OpenOpay : FundingIntent

    data object OpenCheckout : FundingIntent
}

/**
 * One-shot handoffs are consumed by the visible route; no lifecycle callback infers payment
 * success.
 */
data class FundingEffect(
    val accountId: String,
    val routeId: String,
    val kind: Kind,
    val url: String? = null,
) {
    enum class Kind {
        OpenOpay,
        OpenCheckout,
        RefreshWallet,
    }
}

/**
 * Coordinate one cancellable UI visit and stable creation key, with server restoration after app
 * dismissal.
 */
@HiltViewModel
class FundingViewModel
@Inject
constructor(feature: Optional<FundingUseCases>, private val saved: SavedStateHandle) : ViewModel() {
    private val useCases = feature.orElse(null)
    private val mutable = MutableStateFlow(FundingUiState(enabled = useCases != null))
    val uiState = mutable.asStateFlow()
    private val channel = Channel<FundingEffect>(Channel.BUFFERED)
    val effects = channel.receiveAsFlow()
    private var job: Job? = null
    private var visit = 0L
    private var prepared: FundingInput? = null

    /**
     * Serialize commands and retain frozen input after uncertain creation; views only emit typed
     * intents.
     */
    fun accept(intent: FundingIntent) {
        if (intent is FundingIntent.Bind) {
            bind(intent)
            return
        }
        val state = mutable.value
        if (useCases == null || state.accountId == null || state.routeId == null || state.busy) {
            return
        }
        when (intent) {
            is FundingIntent.Bind -> Unit
            is FundingIntent.Amount -> edit { copy(amount = intent.value.take(20)) }
            is FundingIntent.Email -> edit { copy(email = intent.value.take(254)) }
            is FundingIntent.Option -> edit { copy(optionId = intent.id) }
            is FundingIntent.Reference ->
                mutable.value = state.copy(reference = intent.value.take(200), issue = null)
            is FundingIntent.Select ->
                perform { observed(useCases.getFundingRequest(state.accountId, intent.id)) }
            is FundingIntent.Page -> load(intent.offset)
            FundingIntent.Create -> create()
            FundingIntent.SubmitReference ->
                state.request?.let { request ->
                    perform {
                        observed(
                            useCases.submitFundingReference(
                                state.accountId,
                                request.id,
                                state.reference,
                            ),
                        )
                    }
                }
            FundingIntent.Refresh -> {
                val request = state.request
                if (request == null) {
                    load(state.offset)
                } else {
                    perform {
                        observed(useCases.reconcileFundingRequest(state.accountId, request.id))
                    }
                }
            }
            FundingIntent.NewRequest -> {
                prepared = null
                saved["funding.request"] = null
                saved["funding.key"] = UUID.randomUUID().toString()
                mutable.value =
                    state.copy(
                        request = null,
                        amount = "",
                        reference = "",
                        issue = null,
                        submissionUncertain = false,
                    )
            }
            FundingIntent.OpenOpay -> emit(FundingEffect.Kind.OpenOpay)
            FundingIntent.OpenCheckout ->
                state.request?.checkoutUrl?.let { emit(FundingEffect.Kind.OpenCheckout, it) }
        }
    }

    /**
     * Bind an Activity-owned visit without retaining another account's draft or replaying a
     * handoff.
     */
    private fun bind(intent: FundingIntent.Bind) {
        val state = mutable.value
        if (state.accountId == intent.accountId && state.routeId == intent.routeId) {
            return
        }
        visit++
        job?.cancel()
        val sameAccount = saved.get<String>("funding.account") == intent.accountId
        if (!sameAccount) {
            saved["funding.request"] = null
            saved["funding.key"] = null
            prepared = null
        }
        saved["funding.account"] = intent.accountId
        mutable.value =
            if (sameAccount)
                state.copy(accountId = intent.accountId, routeId = intent.routeId, busy = false)
            else
                FundingUiState(
                    enabled = useCases != null,
                    accountId = intent.accountId,
                    routeId = intent.routeId,
                )
        if (useCases != null && intent.accountId != null && intent.routeId != null) {
            load(0)
        }
    }

    /**
     * Load saved server state after navigation/recreation; never automatically submit, reconcile or
     * open another app.
     */
    private fun load(offset: Int) {
        val funding = useCases ?: return
        val account = mutable.value.accountId ?: return
        perform {
            val context = funding.loadFundingContext(account, offset)
            currentCoroutineContext().ensureActive()
            mutable.value =
                mutable.value.copy(
                    context = context,
                    offset = offset,
                    optionId = mutable.value.optionId ?: context.options.firstOrNull()?.id,
                )
            val selected = saved.get<String>("funding.request") ?: return@perform
            observed(funding.getFundingRequest(account, selected))
        }
    }

    /**
     * Freeze validated terms and persist the key before the first POST; explicit retries use that
     * same input.
     */
    private fun create() {
        val funding = useCases ?: return
        val state = mutable.value
        val context = state.context ?: return
        val option = context.options.find { it.id == state.optionId } ?: return
        val account = state.accountId ?: return
        perform {
            val key =
                saved.get<String>("funding.key")
                    ?: UUID.randomUUID().toString().also { saved["funding.key"] = it }
            val input =
                prepared
                    ?: funding.prepareFundingRequest(option, state.amount, state.email, key).also {
                        prepared = it
                    }
            mutable.value = mutable.value.copy(submissionUncertain = true)
            try {
                observed(funding.createFundingRequest(account, context.walletId, input))
            } catch (failure: FundingFailure) {
                currentCoroutineContext().ensureActive()
                if (failure.issue == FundingIssue.InvalidInput) {
                    prepared = null
                    mutable.value = mutable.value.copy(submissionUncertain = false)
                }
                throw failure
            }
        }
    }

    /** Publish server progress and refresh display balances only after a committed credit. */
    private suspend fun observed(request: FundingRequest) {
        currentCoroutineContext().ensureActive()
        saved["funding.request"] = request.id
        mutable.value =
            mutable.value.copy(
                request = request,
                reference = request.customerReference ?: "",
                submissionUncertain = false,
            )
        if (request.status == FundingStatus.Credited) {
            emit(FundingEffect.Kind.RefreshWallet)
        }
    }

    /** Edits cannot mutate an intent that may already exist on the server. */
    private fun edit(change: FundingUiState.() -> FundingUiState) {
        if (mutable.value.submissionUncertain) {
            return
        }
        prepared = null
        mutable.value = mutable.value.change().copy(issue = null)
    }

    /** Retain ownership across suspensions and discard late state after cancellation/navigation. */
    private fun perform(action: suspend () -> Unit) {
        val generation = visit
        mutable.value = mutable.value.copy(busy = true, issue = null)
        job = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: FundingFailure) {
                if (generation == visit) {
                    mutable.value = mutable.value.copy(issue = failure.issue)
                }
            } finally {
                if (generation == visit) {
                    mutable.value = mutable.value.copy(busy = false)
                }
            }
        }
    }

    /**
     * Buffer a single explicit effect with its account and visit; collectors discard effects for
     * hidden/replaced visits.
     */
    private fun emit(kind: FundingEffect.Kind, url: String? = null) {
        val state = mutable.value
        val account = state.accountId ?: return
        val route = state.routeId ?: return
        channel.trySend(FundingEffect(account, route, kind, url))
    }
}
