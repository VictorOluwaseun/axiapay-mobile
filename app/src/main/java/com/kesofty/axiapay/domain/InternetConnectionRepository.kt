package com.kesofty.axiapay.domain

import kotlinx.coroutines.flow.Flow

/** System-validated public internet on the app's default network, not backend availability. */
interface InternetConnectionRepository {
    fun currentIsOnline(): Boolean

    val isOnline: Flow<Boolean>
}
