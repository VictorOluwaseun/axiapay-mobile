package com.kesofty.axiapay.di

import com.kesofty.axiapay.application.purchases.PreparePurchase
import com.kesofty.axiapay.application.purchases.WalletPurchases
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Let the non-demo source set supply real airtime, data and electricity without constructing API
 * infrastructure in demo.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal interface PurchaseFeatureModule {
    /** Absent in demo: the existing demo payment form/actions remain the only purchase path. */
    @BindsOptionalOf fun optionalWalletPurchases(): WalletPurchases
}

/** Construct plain input preparation independently of platform and financial authority. */
@Module
@InstallIn(ViewModelComponent::class)
internal object PurchaseUseCaseModule {
    /** Supply client feedback at the application input boundary. */
    @Provides fun providePreparePurchase(): PreparePurchase = PreparePurchase()
}
