package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.config.LoadRemoteConfiguration
import com.kesofty.axiapay.domain.config.RemoteConfigRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/** Composes the plain configuration use case; flavor modules choose the repository explicitly. */
@Module
@InstallIn(ViewModelComponent::class)
internal object RemoteConfigModule {
    /**
     * Presentation restores and revalidates through the application boundary, never a concrete HTTP
     * adapter.
     */
    @Provides
    fun provideLoadRemoteConfiguration(
        repository: RemoteConfigRepository,
    ): LoadRemoteConfiguration = LoadRemoteConfiguration(repository)
}
