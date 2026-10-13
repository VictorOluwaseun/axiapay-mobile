package com.kesofty.axiapay.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** MVI entry point. Views emit intents; only this owner publishes render state. */
class AxiaViewModel(
    private val observe: ObserveSession,
    private val submit: SubmitDemoPayment,
    private val completeWaiting: CompleteWaitingPayment,
    private val retryBatch: RetryDemoBatchPayment,
    private val setPlan: SetPlanEnabled,
    private val setHidden: SetBalanceVisibility,
    private val setReminders: SetPaymentReminders,
    private val markRead: MarkNotificationsRead,
    private val setConnection: UpdateConnectionState,
    private val setConnectionMode: SetConnectionMode,
    private val internet: ObserveInternetConnection,
    private val prepareSms: PrepareSmsPayment,
    private val sendSms: SendDemoSms,
    private val reconcileSms: ReconcileDemoSms,
    private val forms: PaymentFormPresenter,
    private val navigationStore: NavigationStore,
    private val newRequestId: () -> String,
) : ViewModel() {
    private val session = observe()
    private val initialNavigation = restoreNavigation()
    private val mutableState = MutableStateFlow(AxiaUiState(session.value, initialNavigation))
    val uiState = mutableState.asStateFlow()

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
                mutableState.value = mutableState.value.copy(session = value)
            }
        }
    }

    /** Routes user intents to use cases or navigation; rendering cannot replay an action. */
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
            is AxiaIntent.SetPlan -> handle(setPlan(intent.id, intent.enabled))
            is AxiaIntent.CompleteWaiting -> showReceipt(completeWaiting(intent.id))
            AxiaIntent.RetryBatch -> showReceipt(retryBatch())
            is AxiaIntent.EditPayment -> editPayment(intent.intent)
            AxiaIntent.ConfirmPayment -> confirmPayment()
            is AxiaIntent.OpenSms -> navigate(navigation.copy(sheet = Sheet.Sms(intent.id)))
            is AxiaIntent.SendSms -> showSms(sendSms(intent.id, intent.simulateFailure))
            is AxiaIntent.ReconcileSms -> showSmsOutcome(reconcileSms(intent.id))
            AxiaIntent.SaveDraft -> saveDraft()
            AxiaIntent.ResumeDraft -> resumeDraft()
            AxiaIntent.DismissError -> mutableState.value = mutableState.value.copy(error = null)
        }
    }

    private val navigation
        get() = mutableState.value.navigation

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

    private fun openReceipt(id: String) {
        val sheet =
            when {
                session.value.smsOutbox.any { it.id == id } -> Sheet.Sms(id)
                else -> Sheet.Receipt(id)
            }
        navigate(navigation.copy(sheet = sheet))
    }

    private fun editPayment(intent: PaymentIntent) {
        val sheet = navigation.sheet as? Sheet.Payment ?: return
        navigate(navigation.copy(sheet = Sheet.Payment(forms.reduce(sheet.form, intent))))
    }

    private fun confirmPayment() {
        val sheet = navigation.sheet as? Sheet.Payment ?: return
        if (!sheet.form.reviewing) return
        if (session.value.offline) {
            showSms(prepareSms(sheet.form.input, sheet.form.requestId, sheet.form.smsSim))
            return
        }
        showReceipt(submit(sheet.form.input, sheet.form.requestId))
    }

    private fun formFor(route: String): PaymentFormState {
        val draft = navigation.draft?.takeIf { it.route == route }
        return draft?.let(forms::refresh) ?: forms.create(route, newRequestId())
    }

    private fun closeSheet() {
        val form = (navigation.sheet as? Sheet.Payment)?.form
        navigate(navigation.copy(sheet = null, draft = form ?: navigation.draft))
    }

    private fun saveDraft() {
        val form = (navigation.sheet as? Sheet.Payment)?.form ?: return
        navigate(navigation.copy(sheet = Sheet.Offline, draft = form))
    }

    private fun resumeDraft() {
        val form = navigation.draft ?: return
        navigate(navigation.copy(sheet = Sheet.Payment(forms.refresh(form))))
    }

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

    private fun showSmsOutcome(result: Outcome<Payment>) {
        when (result) {
            is Outcome.Success -> navigate(navigation.copy(sheet = Sheet.Sms(result.value.id)))
            is Outcome.Failure -> report(result.error)
        }
    }

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

    private fun handle(result: Outcome<Unit>) {
        when (result) {
            is Outcome.Success ->
                mutableState.value = mutableState.value.copy(session = session.value)
            is Outcome.Failure -> report(result.error)
        }
    }

    private fun navigate(value: NavigationState) {
        navigationStore.save(value)
        mutableState.value =
            mutableState.value.copy(navigation = value, session = session.value, error = null)
    }

    private fun restoreNavigation(): NavigationState {
        val restored = navigationStore.load() ?: NavigationState()
        val sheet = restored.sheet as? Sheet.Payment ?: return restored
        return restored.copy(sheet = Sheet.Payment(forms.refresh(sheet.form)))
    }

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
                is DemoError.Storage ->
                    "The demo change could not be saved. Your balance has not changed. Try again."
            }
        mutableState.value = mutableState.value.copy(session = session.value, error = message)
    }
}
