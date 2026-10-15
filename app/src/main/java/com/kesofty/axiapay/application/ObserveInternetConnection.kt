package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import java.time.Clock

/** Exposes real device connectivity independently of the demo override. */
class ObserveInternetConnection(private val repository: InternetConnectionRepository) {
    /** Returns the current system-validated connection for initial state resolution. */
    fun currentIsOnline() = repository.currentIsOnline()

    /** Observes connection changes for the lifetime of the caller's collection. */
    operator fun invoke() = repository.isOnline
}

/** Applies observed connectivity while respecting a saved demo override; never submits requests. */
class UpdateConnectionState(private val repository: SessionRepository, private val clock: Clock) {
    /** [offline] describes the real network; the demo preference determines the effective mode. */
    suspend operator fun invoke(offline: Boolean): Outcome<Unit> = repository.transact { session ->
        val updated =
            DemoConnectionPolicy.update(session, offline, session.connectionMode, clock.instant())
        Outcome.Success(SessionChange(updated, Unit))
    }
}
