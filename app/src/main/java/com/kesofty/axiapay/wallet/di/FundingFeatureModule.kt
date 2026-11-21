package com.kesofty.axiapay.wallet.di

import com.kesofty.axiapay.wallet.application.FundingUseCases
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Demo keeps its existing simulated funding flow; only non-demo composition supplies real funding.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal interface FundingFeatureModule {
    /** Expose an optional workflow without constructing HTTP infrastructure in demo. */
    @BindsOptionalOf fun optionalFundingUseCases(): FundingUseCases
}
