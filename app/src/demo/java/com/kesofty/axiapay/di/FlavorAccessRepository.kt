package com.kesofty.axiapay.di

import com.kesofty.axiapay.data.SimulatedAccessRepository
import com.kesofty.axiapay.domain.DemoAccessRepository

/**
 * Selects the demo auth preview for this source set; no real account or credentials are created.
 */
internal fun createFlavorAccessRepository(): DemoAccessRepository = SimulatedAccessRepository()
