package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal class MemorySessionStore(var snapshot: DemoSession? = null) : DemoSessionStore {
    var failSave = false

    override fun load() = snapshot

    override fun save(session: DemoSession) {
        if (failSave) throw IllegalStateException("Simulated save failure")
        snapshot = session
    }
}

internal class MemoryNavigationStore : NavigationStore {
    private var state: NavigationState? = null

    override fun load() = state

    override fun save(state: NavigationState) {
        this.state = state
    }
}

internal class FakeInternetConnection(initialOnline: Boolean = true) :
    InternetConnectionRepository {
    override val isOnline = kotlinx.coroutines.flow.MutableStateFlow(initialOnline)

    override fun currentIsOnline() = isOnline.value
}

internal class DemoTestFixture(
    val store: MemorySessionStore = MemorySessionStore(),
    val connection: FakeInternetConnection = FakeInternetConnection(),
) {
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC)
    val today: LocalDate = LocalDate.now(clock)
    val repository = DemoSessionRepository(store, DemoSessionSeed.create(today))
    val prepare = PreparePayment(clock)
    val submit = SubmitDemoPayment(repository, prepare, clock)
    val complete = CompleteWaitingPayment(repository)
    val batch = RetryDemoBatchPayment(repository, clock)
    val session
        get() = repository.session.value

    fun input(type: PaymentType = PaymentType.Airtime, amount: String = "1000") =
        PaymentInput(
            type,
            recipient = "08000000000",
            amount = amount,
            date = today.plusDays(1),
        )

    /** Builds a ViewModel with controlled connectivity and in-memory session persistence. */
    fun viewModel(navigation: NavigationStore = MemoryNavigationStore()) =
        AxiaViewModel(
            ObserveSession(repository),
            submit,
            complete,
            batch,
            SetPlanEnabled(repository),
            SetBalanceVisibility(repository),
            SetPaymentReminders(repository),
            MarkNotificationsRead(repository),
            UpdateConnectionState(repository, clock),
            SetConnectionMode(repository, connection, clock),
            ObserveInternetConnection(connection),
            PrepareSmsPayment(repository, prepare, clock),
            SendDemoSms(repository, clock),
            ReconcileDemoSms(repository, clock),
            PaymentFormPresenter(prepare, clock),
            navigation,
            { java.util.UUID.randomUUID().toString() },
        )
}

internal fun <T> Outcome<T>.success(): T = (this as Outcome.Success).value
