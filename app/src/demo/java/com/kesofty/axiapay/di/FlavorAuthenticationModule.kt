package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.application.auth.*
import com.kesofty.axiapay.data.SimulatedAccessRepository
import com.kesofty.axiapay.domain.DemoAccessRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/** Permanently selects the isolated auth demonstration; it never receives a real token store. */
@Module
@InstallIn(ViewModelComponent::class)
internal object FlavorAuthenticationModule {
    /** Preserves the existing cancellable simulation and its testable domain port. */
    @Provides
    @ViewModelScoped
    fun provideDemoAccessRepository(): DemoAccessRepository = SimulatedAccessRepository()

    /** Reuses established demo use cases behind the shared presentation operation contract. */
    @Provides
    @ViewModelScoped
    fun provideAccountAccess(
        prepare: PrepareDemoAccess,
        complete: CompleteDemoAccess,
        resend: ResendDemoAccessCode,
    ): AccountAccess = DemoAccountAccess(prepare, complete, resend)
}
