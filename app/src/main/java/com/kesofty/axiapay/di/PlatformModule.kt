package com.kesofty.axiapay.di

import android.content.Context
import android.net.ConnectivityManager
import com.kesofty.axiapay.data.AndroidInternetConnectionRepository
import com.kesofty.axiapay.domain.InternetConnectionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Provides application-safe platform dependencies; it never owns a payment or saved-state session.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PlatformModule {
    /**
     * Owns only an application-safe content resolver; IO scheduling stays explicit and injectable.
     */
    @Provides
    fun provideRecipientImportRepository(
        @ApplicationContext context: Context,
    ): com.kesofty.axiapay.domain.RecipientImportRepository =
        com.kesofty.axiapay.data.imports.AndroidRecipientImportRepository(
            context.contentResolver,
            kotlinx.coroutines.Dispatchers.IO,
            com.kesofty.axiapay.data.imports.RecipientDocumentParser(),
        )

    /** Keeps the existing device-zone clock for all payment and recurrence calculations. */
    @Provides @Singleton fun provideClock(): Clock = Clock.systemDefaultZone()

    /** Uses only the application service; each observer still releases its own network callback. */
    @Provides
    @Singleton
    fun provideInternetConnection(
        @ApplicationContext context: Context,
    ): InternetConnectionRepository =
        AndroidInternetConnectionRepository(
            requireNotNull(context.getSystemService(ConnectivityManager::class.java)),
        )
}
