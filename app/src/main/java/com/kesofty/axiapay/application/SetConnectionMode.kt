package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import java.time.Clock

/** Saves a demo override atomically; Automatic immediately uses the latest device connection. */
class SetConnectionMode(
    private val repository: SessionRepository,
    private val internet: InternetConnectionRepository,
    private val clock: Clock,
) {
    /** Changes connection metadata only; storage failure leaves the previous snapshot intact. */
    suspend operator fun invoke(mode: ConnectionMode): Outcome<Unit> =
        repository.transact { session ->
            val updated =
                DemoConnectionPolicy.update(
                    session,
                    !internet.currentIsOnline(),
                    mode,
                    clock.instant(),
                )
            Outcome.Success(SessionChange(updated, Unit))
        }
}
