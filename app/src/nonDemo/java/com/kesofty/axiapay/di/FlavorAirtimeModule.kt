package com.kesofty.axiapay.di

import android.content.Context
import com.kesofty.axiapay.application.airtime.AirtimePurchases
import com.kesofty.axiapay.data.airtime.AtomicAirtimeRequestStore
import com.kesofty.axiapay.data.airtime.HttpAirtimeRepository
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.domain.airtime.*
import com.kesofty.axiapay.domain.auth.*
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
 * Select real single-airtime purchases at composition while other payment tools remain labelled
 * previews.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object FlavorAirtimeModule {
    /**
     * Bind one plain workflow per UI owner; the durable journal and HTTP pool are shared
     * infrastructure.
     */
    @Provides
    @ViewModelScoped
    fun provideAirtimePurchases(
        authentication: AuthenticationRepository,
        sessions: AuthenticationSessionStore,
        wallets: WalletRepository,
        airtime: AirtimeRepository,
        requestStore: AirtimeRequestStore,
        clock: Clock,
        newRequestId: () -> String,
    ): AirtimePurchases =
        AirtimePurchases(
            authentication,
            sessions,
            wallets,
            airtime,
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
internal object AirtimeInfrastructureModule {
    /** Reuse the existing bounded auth/wallet transport and credential scope. */
    @Provides
    @Singleton
    fun provideAirtimeRepository(http: ApiHttpClient): AirtimeRepository =
        HttpAirtimeRepository(http)

    /**
     * A singleton journal serializes independent ViewModels; no tokens, recipients or balances
     * enter this file.
     */
    @Provides
    @Singleton
    fun provideAirtimeRequestStore(@ApplicationContext context: Context): AirtimeRequestStore =
        AtomicAirtimeRequestStore(
            File(context.noBackupFilesDir, "airtime-requests-v1.json"),
            Dispatchers.IO,
        )
}
