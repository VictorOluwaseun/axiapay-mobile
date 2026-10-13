package com.kesofty.axiapay.di

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.domain.InternetConnectionRepository
import com.kesofty.axiapay.presentation.AxiaViewModel
import java.time.Clock

/**
 * Composition boundary shared by dev, staging and prod as backend features are introduced. For now
 * every feature intentionally uses the same sample experience as demo. Replace individual feature
 * bindings here when their backend contracts exist, retaining the separate demo factory.
 * BuildConfig.FLAVOR can select environment configuration here once real endpoints are supplied;
 * release/debug must never determine whether data is simulated.
 */
internal fun createFlavorViewModel(
    handle: SavedStateHandle,
    connection: InternetConnectionRepository,
    clock: Clock,
): AxiaViewModel = createDemoViewModel(handle, connection, clock)
