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
        SubmitDemoPayment(repository, prepare, clock),
        CompleteWaitingPayment(repository),
        RetryDemoBatchPayment(repository, clock),
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
        SavedNavigationStore(handle),
        { UUID.randomUUID().toString() },
    )
}
