package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.domain.ConnectionMode
import com.kesofty.axiapay.domain.DemoSession
import java.time.LocalDate

sealed interface Sheet {
    data class Payment(val form: PaymentFormState) : Sheet

    data class Sms(val requestId: String) : Sheet

    data class Receipt(val paymentId: String) : Sheet

    data class Explore(val service: String) : Sheet

    data object Notifications : Sheet

    data object Profile : Sheet

    data object Bulk : Sheet

    data object Offline : Sheet
}

data class NavigationState(
    val tab: Int = 0,
    val sheet: Sheet? = null,
    val serviceQuery: String = "",
    val activityFilter: String = "All",
    val draft: PaymentFormState? = null,
)

/**
 * Immutable render contract. Navigation and errors are state, not replayable event streams.
 * Processing describes the current owner only; restoration must never resume a command from it.
 */
data class AxiaUiState(
    val session: DemoSession,
    val navigation: NavigationState,
    val error: String? = null,
    val isProcessing: Boolean = false,
)

sealed interface AxiaIntent {
    data class SelectTab(val tab: Int) : AxiaIntent

    data class OpenService(val route: String) : AxiaIntent

    data class OpenReceipt(val id: String) : AxiaIntent

    data object CloseSheet : AxiaIntent

    data class SearchServices(val query: String) : AxiaIntent

    data class FilterActivity(val filter: String) : AxiaIntent

    data class HideBalance(val hidden: Boolean) : AxiaIntent

    /** Selects a demo override without submitting or reconciling payments. */
    data class SetConnectionMode(val mode: ConnectionMode) : AxiaIntent

    data class SetReminders(val enabled: Boolean) : AxiaIntent

    data class SetPlan(val id: String, val enabled: Boolean) : AxiaIntent

    data class CompleteWaiting(val id: String) : AxiaIntent

    data object RetryBatch : AxiaIntent

    data object DismissError : AxiaIntent

    data class EditPayment(val intent: PaymentIntent) : AxiaIntent

    data class OpenSms(val id: String) : AxiaIntent

    data class SendSms(val id: String, val simulateFailure: Boolean = false) : AxiaIntent

    data class ReconcileSms(val id: String) : AxiaIntent

    data object SaveDraft : AxiaIntent

    data object ResumeDraft : AxiaIntent

    data object ConfirmPayment : AxiaIntent
}

sealed interface PaymentIntent {
    data class Service(val value: String) : PaymentIntent

    data class Provider(val value: String) : PaymentIntent

    data class Destination(val value: String) : PaymentIntent

    data class Recipient(val value: String) : PaymentIntent

    data class Amount(val value: String) : PaymentIntent

    data class DataPlan(val value: String) : PaymentIntent

    data class Schedule(val enabled: Boolean) : PaymentIntent

    data class Frequency(val value: String) : PaymentIntent

    data class Date(val value: LocalDate) : PaymentIntent

    data class Sim(val value: Int) : PaymentIntent

    data object Review : PaymentIntent

    data object Edit : PaymentIntent
}

/** Small presentation-only persistence port makes reducers testable without Android. */
interface NavigationStore {
    fun load(): NavigationState?

    fun save(state: NavigationState)
}
