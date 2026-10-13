package com.kesofty.axiapay.di

import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.presentation.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Composition root: concrete data adapters are wired here, never looked up inside features. */
class AxiaViewModelFactory(private val clock: Clock) : ViewModelProvider.Factory {
    /** Wires saved-state adapters and real connectivity into the demo use cases. */
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass == AxiaViewModel::class.java)
        val handle = extras.createSavedStateHandle()
        val repository =
            DemoSessionRepository(
                SavedStateSessionStore(handle),
                DemoSessionSeed.create(LocalDate.now(clock)),
            )
        val application =
            requireNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
        val connection =
            AndroidInternetConnectionRepository(
                application.getSystemService(ConnectivityManager::class.java),
            )
        val prepare = PreparePayment(clock)
        val viewModel =
            AxiaViewModel(
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
        @Suppress("UNCHECKED_CAST")
        return viewModel as T
    }
}
