package com.kesofty.axiapay.di

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.domain.DemoAccessRepository
import com.kesofty.axiapay.domain.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import java.time.Clock
import java.time.LocalDate

/**
 * Permanently selects demo repositories and actions independently of non-demo backend migration.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object FlavorPaymentModule {
    /**
     * Shares one atomic demo snapshot among this ViewModel's queries/actions, never across owners.
     */
    @Provides
    @ViewModelScoped
    fun provideSessionRepository(handle: SavedStateHandle, clock: Clock): SessionRepository =
        DemoSessionRepository(
            SavedStateSessionStore(handle),
            DemoSessionSeed.create(LocalDate.now(clock)),
        )

    /** Keeps account-entry simulation independent of wallet state and real credentials. */
    @Provides
    @ViewModelScoped
    fun provideDemoAccessRepository(): DemoAccessRepository = SimulatedAccessRepository()

    /** Selects the preserved demo submission policy behind the suspending action contract. */
    @Provides
    fun provideSubmitPayment(
        repository: SessionRepository,
        prepare: PreparePayment,
        clock: Clock,
    ): SubmitPayment {
        val action = SubmitDemoPayment(repository, prepare, clock)
        return SubmitPayment { input, requestId -> action(input, requestId) }
    }

    /** Resumes only the explicitly selected existing waiting payment. */
    @Provides
    fun provideCompletePayment(repository: SessionRepository): CompletePayment {
        val action = CompleteWaitingPayment(repository)
        return CompletePayment { paymentId -> action(paymentId) }
    }

    /** Retries the same illustrative batch request without changing its established identity. */
    @Provides
    fun provideRetryBatchPayment(repository: SessionRepository, clock: Clock): RetryBatchPayment {
        val action = RetryDemoBatchPayment(repository, clock)
        return RetryBatchPayment { action() }
    }

    /** Keeps plan enable/disable updates separate from payment execution. */
    @Provides
    fun provideUpdatePlan(repository: SessionRepository): UpdatePlan {
        val action = SetPlanEnabled(repository)
        return UpdatePlan { planId, enabled -> action(planId, enabled) }
    }

    /** Preserves draft terms, SIM selection and the logical request ID without sending SMS. */
    @Provides
    fun providePrepareSmsRequest(
        repository: SessionRepository,
        prepare: PreparePayment,
        clock: Clock,
    ): PrepareSmsRequest {
        val action = PrepareSmsPayment(repository, prepare, clock)
        return PrepareSmsRequest { input, requestId, sim -> action(input, requestId, sim) }
    }

    /** Keeps SMS transport simulated and distinct from confirmed payment outcomes. */
    @Provides
    fun provideSendSmsRequest(repository: SessionRepository, clock: Clock): SendSmsRequest {
        val action = SendDemoSms(repository, clock)
        return SendSmsRequest { requestId, simulateFailure -> action(requestId, simulateFailure) }
    }

    /** Reconciles only the explicitly requested demo SMS identity under the existing policy. */
    @Provides
    fun provideReconcileSmsRequest(
        repository: SessionRepository,
        clock: Clock,
    ): ReconcileSmsRequest {
        val action = ReconcileDemoSms(repository, clock)
        return ReconcileSmsRequest { requestId -> action(requestId) }
    }
}
