package com.kesofty.axiapay.di

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.domain.InternetConnectionRepository
import com.kesofty.axiapay.presentation.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Builds the preserved sample experience with local demo repositories and simulated payments.
 * Device connectivity still drives offline mode; no payment backend or SMS transport is used.
 * Non-demo builds reuse this wiring until individual features receive backend implementations.
 */
internal fun createDemoViewModel(
    handle: SavedStateHandle,
    connection: InternetConnectionRepository,
    clock: Clock,
): AxiaViewModel {
    val repository =
        DemoSessionRepository(
            SavedStateSessionStore(handle),
            DemoSessionSeed.create(LocalDate.now(clock)),
        )
    val prepare = PreparePayment(clock)
    return AxiaViewModel(
        ObserveSession(repository),
        SubmitPayment { input, id -> SubmitDemoPayment(repository, prepare, clock)(input, id) },
        CompletePayment { id -> CompleteWaitingPayment(repository)(id) },
        RetryBatchPayment { RetryDemoBatchPayment(repository, clock)() },
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
        SavedNavigationStore(handle),
        { UUID.randomUUID().toString() },
    )
}
