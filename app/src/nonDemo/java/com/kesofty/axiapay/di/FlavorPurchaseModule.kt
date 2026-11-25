package com.kesofty.axiapay.di

import android.content.Context
import com.kesofty.axiapay.application.purchases.*
import com.kesofty.axiapay.data.database.AxiaDatabase
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.purchases.AtomicPurchaseRequestStore
import com.kesofty.axiapay.data.purchases.HttpPurchaseRepository
import com.kesofty.axiapay.data.purchases.PurchaseCatalogueRepositoryImpl
import com.kesofty.axiapay.data.purchases.local.RoomPurchaseCatalogueLocalDataSource
import com.kesofty.axiapay.data.purchases.remote.HttpPurchaseCatalogueRemoteDataSource
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.dashboard.DashboardRepository
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.wallet.domain.WalletRepository
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
     * All purchase operations for this screen verify ownership against the same session sources.
     */
    @Provides
    @ViewModelScoped
    fun providePurchaseSessionAccess(
        authentication: AuthenticationRepository,
        sessions: AuthenticationSessionStore,
        wallets: WalletRepository,
        purchases: PurchaseRepository,
        clock: Clock,
    ): PurchaseSessionAccess =
        PurchaseSessionAccess(authentication, sessions, wallets, purchases, clock)

    /** Supply meter verification and saved-payment recovery to live purchase screens. */
    @Provides
    @ViewModelScoped
    fun provideWalletPurchases(
        access: PurchaseSessionAccess,
        purchases: PurchaseRepository,
        requests: PurchaseRequestStore,
        clock: Clock,
    ): WalletPurchases = WalletPurchases(access, purchases, requests, clock)

    /** Expose local form reads, verified refreshes and explicit payment actions to the form. */
    @Provides
    @ViewModelScoped
    fun providePurchaseUseCases(
        access: PurchaseSessionAccess,
        wallets: WalletRepository,
        dashboard: DashboardRepository,
        catalogues: PurchaseCatalogueRepository,
        requests: PurchaseRequestStore,
        purchases: PurchaseRepository,
        payments: WalletPurchases,
        clock: Clock,
        newRequestId: () -> String,
    ): PurchaseUseCases {
        val contexts = PurchaseContextReader(catalogues, requests)
        return PurchaseUseCases(
            loadCachedPurchaseContext = LoadCachedPurchaseContext(access, wallets, contexts),
            refreshPurchaseContext =
                RefreshPurchaseContext(access, wallets, dashboard, catalogues, contexts),
            preparePurchase = PreparePurchase(),
            reviewPurchase = ReviewPurchase(access, wallets, purchases, clock, newRequestId),
            submitPurchase = SubmitPurchase(access, purchases, requests, clock),
            payments = payments,
        )
    }
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
     * Share durable catalogue coordination across live purchase owners, with no Activity
     * references.
     */
    @Provides
    @Singleton
    fun providePurchaseCatalogueRepository(
        http: ApiHttpClient,
        database: AxiaDatabase,
        sessions: AuthenticationSessionStore,
        clock: Clock,
    ): PurchaseCatalogueRepository =
        PurchaseCatalogueRepositoryImpl(
            HttpPurchaseCatalogueRemoteDataSource(http),
            RoomPurchaseCatalogueLocalDataSource(database, clock),
            sessions,
            clock,
        )

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
