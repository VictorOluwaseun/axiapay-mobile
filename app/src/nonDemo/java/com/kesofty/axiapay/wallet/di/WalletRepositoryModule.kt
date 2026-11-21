package com.kesofty.axiapay.wallet.di

import com.kesofty.axiapay.data.database.AxiaDatabase
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.wallet.data.WalletRepositoryImpl
import com.kesofty.axiapay.wallet.data.local.RoomWalletLocalDataSource
import com.kesofty.axiapay.wallet.data.remote.HttpWalletRemoteDataSource
import com.kesofty.axiapay.wallet.domain.WalletRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/** Shares the dev wallet balance adapter across account, purchase and funding workflows. */
@Module
@InstallIn(SingletonComponent::class)
internal object WalletRepositoryModule {
    /** Reads the authenticated server wallet without borrowing or changing the demo session. */
    @Provides
    @Singleton
    fun provideWalletRepository(
        http: ApiHttpClient,
        database: AxiaDatabase,
        clock: Clock,
    ): WalletRepository =
        WalletRepositoryImpl(
            HttpWalletRemoteDataSource(http),
            RoomWalletLocalDataSource(database, clock),
        )
}
