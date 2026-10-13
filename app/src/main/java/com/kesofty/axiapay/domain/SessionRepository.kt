package com.kesofty.axiapay.domain

import kotlinx.coroutines.flow.StateFlow

/** One atomic snapshot contains balance, receipts, plans and demo preferences. */
interface SessionRepository {
    val session: StateFlow<DemoSession>

    /**
     * Evaluate once against the latest snapshot and commit all changes or none. Failures never
     * publish.
     */
    fun <T> transact(change: (DemoSession) -> Outcome<SessionChange<T>>): Outcome<T>
}
