package com.kesofty.axiapay.wallet.di

import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.wallet.application.WalletFunding
import com.kesofty.axiapay.wallet.data.FundingRepositoryImpl
import com.kesofty.axiapay.wallet.domain.FundingRepository
import com.kesofty.axiapay.wallet.domain.WalletRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import java.time.Clock

/** Compose real owner-scoped funding for dev with bounded transport and protected sessions. */
@Module
@InstallIn(ViewModelComponent::class)
internal object FlavorFundingModule {
    /** Borrow the lifecycle-managed HTTP client; requests and recovery live on the server. */
    @Provides
    @ViewModelScoped
    fun repository(http: ApiHttpClient): FundingRepository = FundingRepositoryImpl(http)

    /**
     * Construct the plain application workflow; no Hilt annotations enter application/domain code.
     */
    @Provides
    @ViewModelScoped
    fun workflow(
        authentication: AuthenticationRepository,
        sessions: AuthenticationSessionStore,
        wallets: WalletRepository,
        funding: FundingRepository,
        clock: Clock,
    ): WalletFunding = WalletFunding(authentication, sessions, wallets, funding, clock)
}
