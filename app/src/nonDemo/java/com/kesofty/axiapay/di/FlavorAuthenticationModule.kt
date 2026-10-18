package com.kesofty.axiapay.di

import android.content.Context
import android.os.Build
import com.kesofty.axiapay.BuildConfig
import com.kesofty.axiapay.application.auth.AccountAccess
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.data.auth.*
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.http.ApiRequestIdGenerator
import com.kesofty.axiapay.data.wallet.HttpWalletRepository
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.wallet.WalletRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/** Composes the real auth workflow independently of the still-simulated payment owner. */
@Module
@InstallIn(ViewModelComponent::class)
internal object FlavorAuthenticationModule {
    /** Constructs plain application orchestration; no feature policy belongs in the Hilt graph. */
    @Provides
    @ViewModelScoped
    fun provideAccountAccess(
        repository: AuthenticationRepository,
        sessions: AuthenticationSessionStore,
        clock: Clock,
        wallets: WalletRepository,
    ): AccountAccess = ServerAccountAccess(repository, sessions, clock, wallets)
}

/** Owns the application-wide HTTP pool and protected auth session, never demo payment snapshots. */
@Module
@InstallIn(SingletonComponent::class)
internal object AuthenticationInfrastructureModule {
    /** Auth calls are bounded, uncached and never replayed or redirected with credentials. */
    @Provides
    @Singleton
    fun provideApiHttpClient(clock: Clock): ApiHttpClient {
        val client =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        return ApiHttpClient(
            BuildConfig.AUTH_API_BASE_URL,
            BuildConfig.AUTH_ALLOW_LOCAL_HTTP,
            client,
            Dispatchers.IO,
            ApiRequestIdGenerator(Build.MANUFACTURER, Build.MODEL, clock),
        )
    }

    /** Shares the bounded transport while keeping auth DTO mapping inside its repository. */
    @Provides
    @Singleton
    fun provideAuthenticationRepository(
        http: ApiHttpClient,
        clock: Clock,
    ): AuthenticationRepository = HttpAuthenticationRepository(http, clock)

    /** Reads the authenticated server wallet without borrowing or changing the demo session. */
    @Provides
    @Singleton
    fun provideWalletRepository(http: ApiHttpClient): WalletRepository = HttpWalletRepository(http)

    /** Stores one real auth record per independently installed flavor using application context. */
    @Provides
    @Singleton
    fun provideAuthenticationSessionStore(
        @ApplicationContext context: Context,
    ): AuthenticationSessionStore = KeystoreAuthenticationSessionStore(context, Dispatchers.IO)
}
