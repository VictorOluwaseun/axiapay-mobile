package com.kesofty.axiapay.domain

import kotlinx.coroutines.flow.StateFlow

/** One atomic snapshot contains balance, receipts, plans and demo preferences. */
interface SessionRepository {
    /** Read-only committed snapshots; consumers cannot mutate their backing collections. */
    val session: StateFlow<DemoSession>

    /**
     * Loads the owner snapshot before accepting actions; failure never replaces it with fixtures.
     */
    suspend fun initialize(): Outcome<Unit> = Outcome.Success(Unit)

    /**
     * Evaluate once against the latest snapshot and commit all changes or none. Failures never
     * publish.
     */
    suspend fun <T> transact(change: (DemoSession) -> Outcome<SessionChange<T>>): Outcome<T>
}
