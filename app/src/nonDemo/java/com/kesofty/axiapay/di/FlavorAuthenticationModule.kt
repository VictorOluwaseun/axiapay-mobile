package com.kesofty.axiapay.di

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Room
import com.kesofty.axiapay.BuildConfig
import com.kesofty.axiapay.application.auth.AccountAccess
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.data.AtomicTextCheckpoint
import com.kesofty.axiapay.data.auth.*
import com.kesofty.axiapay.data.config.RemoteConfigRepositoryImpl
import com.kesofty.axiapay.data.database.*
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.http.ApiHttpLoggingInterceptor
import com.kesofty.axiapay.data.http.ApiRequestIdGenerator
import com.kesofty.axiapay.data.wallet.HttpWalletRepository
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.config.RemoteConfigRepository
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
    /** API calls are bounded, uncached and never replayed or redirected with credentials. */
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
                .apply {
                    if (BuildConfig.DEBUG && BuildConfig.FLAVOR == "dev") {
                        addInterceptor(ApiHttpLoggingInterceptor { Log.d("AxiaHttp", it) })
                    }
                }
                .build()
        return ApiHttpClient(
            BuildConfig.API_BASE_URL,
            BuildConfig.API_ALLOW_LOCAL_HTTP,
            client,
            Dispatchers.IO,
            ApiRequestIdGenerator(Build.MANUFACTURER, Build.MODEL, clock),
        )
    }

    /**
     * Keep one atomic public-config record per installed flavor, validating the API scope on read.
     */
    @Provides
    @Singleton
    fun provideRemoteConfigRepository(
        http: ApiHttpClient,
        @ApplicationContext context: Context,
        database: AxiaDatabase,
        clock: Clock,
    ): RemoteConfigRepository =
        RemoteConfigRepositoryImpl(
            http,
            database.configuration(),
            clock,
            legacyCheckpoint =
                AtomicTextCheckpoint(
                    File(context.noBackupFilesDir, "remote-config/snapshot.json"),
                    Dispatchers.IO,
                ),
        )

    /**
     * Keep durable snapshots outside OS backup and isolate records by the configured API identity.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AxiaDatabase =
        Room.databaseBuilder(
                context,
                AxiaDatabase::class.java,
                File(context.noBackupFilesDir, "dashboard/snapshots.db")
                    .apply { parentFile?.mkdirs() }
                    .absolutePath,
            )
            .build()

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
