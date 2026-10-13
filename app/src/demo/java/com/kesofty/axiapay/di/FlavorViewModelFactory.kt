package com.kesofty.axiapay.di

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.domain.InternetConnectionRepository
import com.kesofty.axiapay.presentation.AxiaViewModel
import java.time.Clock

/**
 * Preserves the sample experience independently of backend migration in non-demo builds. Keep this
 * path bound to demo implementations as real services are added elsewhere.
 */
internal fun createFlavorViewModel(
    handle: SavedStateHandle,
    connection: InternetConnectionRepository,
    clock: Clock,
): AxiaViewModel = createDemoViewModel(handle, connection, clock)
