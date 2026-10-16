package com.kesofty.axiapay.di

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
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
    /** Bundles import examples only with demo and uses application-owned storage handles. */
    @Provides
    fun provideRecipientSamples(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): com.kesofty.axiapay.domain.RecipientSampleRepository =
        com.kesofty.axiapay.data.imports.BundledRecipientSampleRepository(
            context.assets,
            context.contentResolver,
            kotlinx.coroutines.Dispatchers.IO,
        )

    /**
     * Shares one atomic demo snapshot among this ViewModel's queries/actions, never across owners.
     */
    @Provides
    @ViewModelScoped
    fun provideSessionRepository(
        handle: SavedStateHandle,
        clock: Clock,
        @SessionCheckpoint checkpoint: com.kesofty.axiapay.domain.TextCheckpoint,
    ): SessionRepository =
        CheckpointSessionRepository(
            checkpoint,
            DemoSessionSeed.create(LocalDate.now(clock)),
            kotlinx.coroutines.Dispatchers.IO,
            handle.get<String>("demo.session.v1"),
        )

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

    /** Selects the demo queue policy while sharing the existing wallet repository. */
    @Provides
    fun provideQueueBulkBatch(
        repository: SessionRepository,
        prepare: PrepareBulkBatch,
    ): QueueBulkBatch {
        val action = QueueDemoBulkBatch(repository, prepare)
        return QueueBulkBatch { action(it) }
    }

    /**
     * Retries only known failures after explicit review; successful recipients retain their IDs.
     */
    @Provides
    fun provideRetryBulkBatch(repository: SessionRepository): RetryBulkBatch =
        RetryBulkBatch { id ->
            RetryDemoBulkBatch(repository)(id)
        }

    /** Keeps execution simulated and owned by the invoking ViewModel's cancellable scope. */
    @Provides
    fun provideRunBulkBatch(repository: SessionRepository): RunBulkBatch {
        val action = RunDemoBulkBatch(repository)
        return RunBulkBatch { action(it) }
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
