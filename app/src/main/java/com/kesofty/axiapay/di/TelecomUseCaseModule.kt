package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.telecom.PrepareTelecom
import com.kesofty.axiapay.application.telecom.TelecomPurchases
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Let the non-demo source set supply real airtime/data without constructing API infrastructure in
 * demo.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal interface TelecomFeatureModule {
    /** Absent in demo: the existing demo payment form/actions remain the only airtime path. */
    @BindsOptionalOf fun optionalTelecomPurchases(): TelecomPurchases
}

/** Construct plain input preparation independently of platform and financial authority. */
@Module
@InstallIn(ViewModelComponent::class)
internal object TelecomUseCaseModule {
    /** Supply client feedback at the application input boundary. */
    @Provides fun providePrepareTelecom(): PrepareTelecom = PrepareTelecom()
}
