package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.purchases.PurchaseUseCases
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Let the non-demo source set supply real airtime, data and electricity without constructing API
 * infrastructure in demo.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal interface PurchaseUseCaseModule {
    /** The live form can load catalogues and handle payment intents in the non-demo flavor. */
    @BindsOptionalOf fun optionalPurchaseUseCases(): PurchaseUseCases
}
