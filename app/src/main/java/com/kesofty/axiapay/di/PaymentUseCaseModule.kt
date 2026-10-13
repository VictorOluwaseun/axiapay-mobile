package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.InternetConnectionRepository
import com.kesofty.axiapay.domain.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import java.time.Clock

/**
 * Wires shared payment queries and preferences while keeping application classes framework-free.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object PaymentUseCaseModule {
    /** Observes the same scoped snapshot that mutation use cases commit. */
    @Provides
    fun provideObserveSession(repository: SessionRepository): ObserveSession =
        ObserveSession(repository)

    /** Supplies existing client form validation without moving rules into the injection graph. */
    @Provides fun providePreparePayment(clock: Clock): PreparePayment = PreparePayment(clock)

    /** Changes balance visibility through the existing atomic session operation. */
    @Provides
    fun provideSetBalanceVisibility(repository: SessionRepository): SetBalanceVisibility =
        SetBalanceVisibility(repository)

    /** Preserves the existing demo reminder preference workflow. */
    @Provides
    fun provideSetPaymentReminders(repository: SessionRepository): SetPaymentReminders =
        SetPaymentReminders(repository)

    /** Marks demo notifications through the same session owner used for rendering. */
    @Provides
    fun provideMarkNotificationsRead(repository: SessionRepository): MarkNotificationsRead =
        MarkNotificationsRead(repository)

    /** Updates connection metadata without submitting or reconciling payments. */
    @Provides
    fun provideUpdateConnectionState(
        repository: SessionRepository,
        clock: Clock,
    ): UpdateConnectionState = UpdateConnectionState(repository, clock)

    /**
     * Resolves the existing automatic/manual preview policy using the current platform connection.
     */
    @Provides
    fun provideSetConnectionMode(
        repository: SessionRepository,
        connection: InternetConnectionRepository,
        clock: Clock,
    ): SetConnectionMode = SetConnectionMode(repository, connection, clock)

    /**
     * Exposes connectivity through the application boundary; subscription lifetime belongs to its
     * caller.
     */
    @Provides
    fun provideObserveInternetConnection(
        connection: InternetConnectionRepository,
    ): ObserveInternetConnection = ObserveInternetConnection(connection)
}
