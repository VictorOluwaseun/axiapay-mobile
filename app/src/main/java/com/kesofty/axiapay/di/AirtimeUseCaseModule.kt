package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.airtime.AirtimePurchases
import com.kesofty.axiapay.application.airtime.PrepareAirtime
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Let the non-demo source set supply real airtime without constructing API infrastructure in demo.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal interface AirtimeFeatureModule {
    /** Absent in demo: the existing demo payment form/actions remain the only airtime path. */
    @BindsOptionalOf fun optionalAirtimePurchases(): AirtimePurchases
}

/** Construct plain input preparation independently of platform and financial authority. */
@Module
@InstallIn(ViewModelComponent::class)
internal object AirtimeUseCaseModule {
    /** Supply client feedback at the application input boundary. */
    @Provides fun providePrepareAirtime(): PrepareAirtime = PrepareAirtime()
}
