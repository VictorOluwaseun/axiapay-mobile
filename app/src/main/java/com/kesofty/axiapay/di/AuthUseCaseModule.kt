package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.CompleteDemoAccess
import com.kesofty.axiapay.application.PrepareDemoAccess
import com.kesofty.axiapay.application.ResendDemoAccessCode
import com.kesofty.axiapay.domain.DemoAccessRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Supplies the existing auth preview use cases without sharing or resetting the payment session.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object AuthUseCaseModule {
    /** Validates contact/method inputs and chooses direct password sign-in or code verification. */
    @Provides
    fun providePrepareDemoAccess(repository: DemoAccessRepository): PrepareDemoAccess =
        PrepareDemoAccess(repository)

    /** Completes only the existing demo-code preview; no real account or token is created. */
    @Provides
    fun provideCompleteDemoAccess(repository: DemoAccessRepository): CompleteDemoAccess =
        CompleteDemoAccess(repository)

    /** Resets verification independently of the discarded signup password. */
    @Provides
    fun provideResendDemoAccessCode(repository: DemoAccessRepository): ResendDemoAccessCode =
        ResendDemoAccessCode(repository)
}
