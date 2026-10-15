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
    val prepareBulk = PrepareBulkBatch(prepare, clock)
    val queueBulk = QueueDemoBulkBatch(repository, prepareBulk)
    val retryBulk = RetryDemoBulkBatch(repository)
    val runBulk = RunDemoBulkBatch(repository)

    /** Creates four airtime examples with distinct stable row identities. */
    fun bulkDraft(id: String = "new-batch"): BulkDraft {
        var row = 0
        return BulkDraft(
            id,
            "Family essentials",
            BulkServiceCatalog.sampleRecipients(BulkService.Airtime) { "row-${row++}" },
        )
    }

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
    fun viewModel(
        navigation: NavigationStore = MemoryNavigationStore(),
        submitAction: SubmitPayment = SubmitPayment { input, id -> submit(input, id) },
    ) =
        AxiaViewModel(
            ObserveSession(repository),
            submitAction,
            CompletePayment { complete(it) },
            RetryBatchPayment { batch() },
            UpdatePlan { id, enabled -> SetPlanEnabled(repository)(id, enabled) },
            SetBalanceVisibility(repository),
            SetPaymentReminders(repository),
            MarkNotificationsRead(repository),
            UpdateConnectionState(repository, clock),
            SetConnectionMode(repository, connection, clock),
            ObserveInternetConnection(connection),
            PrepareSmsRequest { input, id, sim ->
                PrepareSmsPayment(repository, prepare, clock)(input, id, sim)
            },
            SendSmsRequest { id, failure -> SendDemoSms(repository, clock)(id, failure) },
            ReconcileSmsRequest { id -> ReconcileDemoSms(repository, clock)(id) },
            PaymentFormPresenter(prepare, clock),
            navigation,
            { java.util.UUID.randomUUID().toString() },
        )
}

internal fun <T> Outcome<T>.success(): T = (this as Outcome.Success).value
