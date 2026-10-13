package com.kesofty.axiapay.di

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.application.PreparePayment
import com.kesofty.axiapay.presentation.NavigationStore
import com.kesofty.axiapay.presentation.PaymentFormPresenter
import com.kesofty.axiapay.presentation.SavedNavigationStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import java.time.Clock
import java.util.UUID

/** Supplies presentation collaborators with the payment ViewModel's own saved-state handle. */
@Module
@InstallIn(ViewModelComponent::class)
internal object PaymentPresentationModule {
    /** Restores navigation separately from the session, within the same ViewModel's handle. */
    @Provides
    @ViewModelScoped
    fun provideNavigationStore(handle: SavedStateHandle): NavigationStore =
        SavedNavigationStore(handle)

    /**
     * Keeps form reduction in presentation and delegates validation to its application use case.
     */
    @Provides
    fun providePaymentFormPresenter(prepare: PreparePayment, clock: Clock): PaymentFormPresenter =
        PaymentFormPresenter(prepare, clock)

    /** Allocates a UUID only when the existing reducer explicitly opens a new logical request. */
    @Provides
    fun providePaymentRequestIdGenerator(): () -> String = { UUID.randomUUID().toString() }
}
