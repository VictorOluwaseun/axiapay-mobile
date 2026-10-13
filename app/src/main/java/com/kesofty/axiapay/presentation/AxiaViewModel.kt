package com.kesofty.axiapay.presentation

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** MVI entry point. Views emit intents; only this owner publishes render state. */
class AxiaViewModel(
    private val observe: ObserveSession,
    private val submit: SubmitPayment,
    private val completeWaiting: CompletePayment,
    private val retryBatch: RetryBatchPayment,
    private val setPlan: UpdatePlan,
    private val setHidden: SetBalanceVisibility,
    private val setReminders: SetPaymentReminders,
    private val markRead: MarkNotificationsRead,
    private val setConnection: UpdateConnectionState,
    private val setConnectionMode: SetConnectionMode,
    private val internet: ObserveInternetConnection,
    private val prepareSms: PrepareSmsRequest,
    private val sendSms: SendSmsRequest,
    private val reconcileSms: ReconcileSmsRequest,
    private val forms: PaymentFormPresenter,
    private val navigationStore: NavigationStore,
    private val newRequestId: () -> String,
) : ViewModel() {
    private val session = observe()
    private val initialNavigation = restoreNavigation()
    private val mutableState = MutableStateFlow(AxiaUiState(session.value, initialNavigation))
    val uiState = mutableState.asStateFlow()
    // Navigation revisions prevent a result from taking over a screen opened after submission.
    private var navigationRevision = 0L

    init {
        // Refresh persisted connection metadata before the first frame, without touching
        // navigation.
        handle(setConnection(!internet.currentIsOnline()))
        viewModelScope.launch {
            internet()
                .catch { cause ->
                    handle(setConnection(true))
                    report(DemoError.ConnectionMonitoring(cause))
                }
                .collect { online -> handle(setConnection(!online)) }
        }
        viewModelScope.launch {
            session.collect { value ->
                mutableState.update { it.copy(session = value) }
            }
        }
    }

    /** Routes user intents to use cases or navigation; rendering cannot replay an action. */
    @MainThread
    fun accept(intent: AxiaIntent) {
        when (intent) {
            is AxiaIntent.SelectTab ->
                navigate(
                    navigation.copy(
                        tab = intent.tab.coerceIn(0, 3),
                        sheet = null,
                        serviceQuery = "",
                        activityFilter = "All",
                    ),
                )
            is AxiaIntent.OpenService -> open(intent.route)
            is AxiaIntent.OpenReceipt -> openReceipt(intent.id)
            AxiaIntent.CloseSheet -> closeSheet()
            is AxiaIntent.SearchServices -> navigate(navigation.copy(serviceQuery = intent.query))
            is AxiaIntent.FilterActivity ->
                navigate(navigation.copy(activityFilter = intent.filter))
            is AxiaIntent.HideBalance -> handle(setHidden(intent.hidden))
            is AxiaIntent.SetConnectionMode -> handle(setConnectionMode(intent.mode))
            is AxiaIntent.SetReminders -> handle(setReminders(intent.enabled))
            is AxiaIntent.SetPlan -> executeAction({ setPlan(intent.id, intent.enabled) }, ::handle)
            is AxiaIntent.CompleteWaiting ->
                executeAction({ completeWaiting(intent.id) }, ::showReceipt)
            AxiaIntent.RetryBatch -> executeAction({ retryBatch() }, ::showReceipt)
            is AxiaIntent.EditPayment -> editPayment(intent.intent)
            AxiaIntent.ConfirmPayment -> confirmPayment()
            is AxiaIntent.OpenSms -> navigate(navigation.copy(sheet = Sheet.Sms(intent.id)))
            is AxiaIntent.SendSms ->
                executeAction({ sendSms(intent.id, intent.simulateFailure) }, ::showSms)
            is AxiaIntent.ReconcileSms ->
                executeAction({ reconcileSms(intent.id) }, ::showSmsOutcome)
            AxiaIntent.SaveDraft -> saveDraft()
            AxiaIntent.ResumeDraft -> resumeDraft()
            AxiaIntent.DismissError -> mutableState.update { it.copy(error = null) }
        }
    }

    /**
     * Runs one explicit action per owner. Busy state is ephemeral, never restored or replayed. Only
     * the originating navigation revision consumes its result; repository state still updates after
     * navigation. Cancellation says nothing about a server outcome and is never a failure.
     */
    private fun <T> executeAction(
        action: suspend () -> Outcome<T>,
        consume: (Outcome<T>) -> Unit,
    ) {
        if (mutableState.value.isProcessing) return
        val originRevision = navigationRevision
        mutableState.update { it.copy(isProcessing = true, error = null) }
        viewModelScope.launch {
            try {
                val result = action()
                if (originRevision == navigationRevision) consume(result)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                if (originRevision == navigationRevision)
                    report(DemoError.OperationUnconfirmed(cause))
            } finally {
                mutableState.update { it.copy(isProcessing = false, session = session.value) }
            }
        }
    }

    private val navigation
        get() = mutableState.value.navigation

    /** Opens a destination; notification read state changes only through its use case. */
    private fun open(route: String) {
        val sheet =
            when (route) {
                "notifications" -> Sheet.Notifications
                "profile" -> Sheet.Profile
                "bulk" -> Sheet.Bulk
                "offline" -> Sheet.Offline
                "Insurance",
                "Education",
                "Convert airtime",
                "Merchants" -> Sheet.Explore(route)
                else -> Sheet.Payment(formFor(route))
            }
        navigate(navigation.copy(sheet = sheet))
        if (sheet == Sheet.Notifications) handle(markRead())
    }

    /** Routes SMS identities to their transport status and other identities to receipts. */
    private fun openReceipt(id: String) {
        val sheet =
            when {
                session.value.smsOutbox.any { it.id == id } -> Sheet.Sms(id)
                else -> Sheet.Receipt(id)
            }
        navigate(navigation.copy(sheet = sheet))
    }

    /** Reduces one form intent without performing a payment. */
    private fun editPayment(intent: PaymentIntent) {
        val sheet = navigation.sheet as? Sheet.Payment ?: return
        navigate(navigation.copy(sheet = Sheet.Payment(forms.reduce(sheet.form, intent))))
    }

    /** Captures reviewed terms and routes an explicit confirmation to online or SMS work. */
    private fun confirmPayment() {
        val sheet = navigation.sheet as? Sheet.Payment ?: return
        if (!sheet.form.reviewing) return
        if (session.value.offline) {
            executeAction(
                { prepareSms(sheet.form.input, sheet.form.requestId, sheet.form.smsSim) },
                ::showSms,
            )
            return
        }
        executeAction({ submit(sheet.form.input, sheet.form.requestId) }, ::showReceipt)
    }

    /** Resumes a matching draft or assigns a fresh identity to a new form. */
    private fun formFor(route: String): PaymentFormState {
        val draft = navigation.draft?.takeIf { it.route == route }
        return draft?.let(forms::refresh) ?: forms.create(route, newRequestId())
    }

    /** Closes the sheet while retaining any unsubmitted form. */
    private fun closeSheet() {
        val form = (navigation.sheet as? Sheet.Payment)?.form
        navigate(navigation.copy(sheet = null, draft = form ?: navigation.draft))
    }

    /** Saves the current form and returns to the offline panel without submitting it. */
    private fun saveDraft() {
        val form = (navigation.sheet as? Sheet.Payment)?.form ?: return
        navigate(navigation.copy(sheet = Sheet.Offline, draft = form))
    }

    /** Revalidates the retained form without executing a request. */
    private fun resumeDraft() {
        val form = navigation.draft ?: return
        navigate(navigation.copy(sheet = Sheet.Payment(forms.refresh(form))))
    }

    /** Consumes a prepared or sent SMS result without treating transport as payment success. */
    private fun showSms(result: Outcome<SmsRequest>) {
        when (result) {
            is Outcome.Success ->
                navigate(
                    navigation.copy(
                        sheet = Sheet.Sms(result.value.id),
                        draft = navigation.draft?.takeUnless { it.requestId == result.value.id },
                    ),
                )
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Keeps reconciliation results on the SMS status sheet. */
    private fun showSmsOutcome(result: Outcome<Payment>) {
        when (result) {
            is Outcome.Success -> navigate(navigation.copy(sheet = Sheet.Sms(result.value.id)))
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Shows a recorded result and removes only its matching draft. */
    private fun showReceipt(result: Outcome<Payment>) {
        when (result) {
            is Outcome.Success ->
                navigate(
                    navigation.copy(
                        sheet = Sheet.Receipt(result.value.id),
                        draft = navigation.draft?.takeUnless { it.requestId == result.value.id },
                    ),
                )
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Publishes a committed preference change or its explicit error. */
    private fun handle(result: Outcome<Unit>) {
        when (result) {
            is Outcome.Success -> mutableState.update { it.copy(session = session.value) }
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Saves presentation state and advances the result-consumption revision. */
    private fun navigate(value: NavigationState) {
        navigationStore.save(value)
        navigationRevision += 1
        mutableState.update { it.copy(navigation = value, session = session.value, error = null) }
    }

    /** Restores fields and revalidates a form without replaying commands. */
    private fun restoreNavigation(): NavigationState {
        val restored = navigationStore.load() ?: NavigationState()
        val sheet = restored.sheet as? Sheet.Payment ?: return restored
        return restored.copy(sheet = Sheet.Payment(forms.refresh(sheet.form)))
    }

    /** Maps explicit errors to safe display text without declaring uncertain payments failed. */
    private fun report(error: DemoError) {
        val message =
            when (error) {
                DemoError.OnlineRequired ->
                    "Reconnect to use this action. Your saved details and plans are still here."
                DemoError.SmsUnsupported ->
                    "This SMS demo supports one-time airtime and data payments. Save other payments as drafts."
                DemoError.SmsAlreadySubmitted ->
                    "This request is in your SMS outbox. Check its status there instead of paying again."
                DemoError.SmsNotSent ->
                    "Send this SMS request before checking for a payment result."
                DemoError.RequestExpired ->
                    "This unsent SMS review has expired. Start a new payment and review the details again."
                DemoError.RetryLimit ->
                    "Three demo attempts have failed. Nothing was sent. Start a new payment when mobile service is available."
                DemoError.InvalidAmount -> "Enter a valid amount between ₦1 and ₦1,000,000."
                DemoError.InvalidRecipient -> "Check the recipient details and try again."
                DemoError.InvalidDate -> "Choose a future payment date."
                DemoError.InvalidProvider -> "Choose one of the sample providers."
                DemoError.InvalidPlan -> "Choose a sample data plan again."
                DemoError.NotFound -> "This demo payment or plan could not be found."
                DemoError.InsufficientFunds -> "Add money before completing this payment."
                DemoError.RequestConflict ->
                    "This request was already used for different payment details. Open a new payment."
                is DemoError.ConnectionMonitoring ->
                    "Internet monitoring stopped. Your drafts and pending payments are safe. Reopen the app to check your connection."
                is DemoError.OperationUnconfirmed ->
                    "The result could not be confirmed. Check Activity or the SMS outbox before trying again."
                is DemoError.Storage ->
                    "The demo change could not be saved. Your balance has not changed. Try again."
            }
        mutableState.update { it.copy(session = session.value, error = message) }
    }
}
