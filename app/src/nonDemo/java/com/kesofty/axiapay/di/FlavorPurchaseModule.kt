package com.kesofty.axiapay.di

import android.content.Context
import com.kesofty.axiapay.application.purchases.WalletPurchases
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.purchases.AtomicPurchaseRequestStore
import com.kesofty.axiapay.data.purchases.HttpPurchaseRepository
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.domain.wallet.WalletRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/**
 * Select real immediate airtime, data and electricity purchases at composition while other payment
 * tools remain labelled previews.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object FlavorPurchaseModule {
    /**
     * Bind one plain workflow per UI owner; the durable journal and HTTP pool are shared
     * infrastructure.
     */
    @Provides
    @ViewModelScoped
    fun provideWalletPurchases(
        authentication: AuthenticationRepository,
        sessions: AuthenticationSessionStore,
        wallets: WalletRepository,
        purchases: PurchaseRepository,
        requestStore: PurchaseRequestStore,
        clock: Clock,
        newRequestId: () -> String,
    ): WalletPurchases =
        WalletPurchases(
            authentication,
            sessions,
            wallets,
            purchases,
            requestStore,
            clock,
            newRequestId,
        )
}

/**
 * Own non-demo HTTP adaptation and atomic recovery references without retaining Activity or View
 * state.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PurchaseInfrastructureModule {
    /** Reuse the existing bounded auth/wallet transport and credential scope. */
    @Provides
    @Singleton
    fun providePurchaseRepository(http: ApiHttpClient): PurchaseRepository =
        HttpPurchaseRepository(http)

    /**
     * A singleton journal serializes independent ViewModels; no tokens, recipients or balances
     * enter this file. Retain its legacy filename to preserve existing airtime requests.
     */
    @Provides
    @Singleton
    fun providePurchaseRequestStore(@ApplicationContext context: Context): PurchaseRequestStore =
        AtomicPurchaseRequestStore(
            File(context.noBackupFilesDir, "airtime-requests-v1.json"),
            Dispatchers.IO,
        )
}
