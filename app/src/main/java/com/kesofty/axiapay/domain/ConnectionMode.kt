package com.kesofty.axiapay.domain

import java.time.Instant

/** Demo connection preference; forced modes simulate app behaviour, never device connectivity. */
enum class ConnectionMode {
    Automatic,
    ForceOffline,
    ForceOnline,
}

/** Resolves the demo preference without changing payment, outbox, or plan state. */
object DemoConnectionPolicy {
    /** Updates effective mode and timestamps only on entry into offline mode. */
    fun update(
        session: DemoSession,
        networkOffline: Boolean,
        mode: ConnectionMode,
        now: Instant,
    ): DemoSession {
        val offline =
            when (mode) {
                ConnectionMode.Automatic -> networkOffline
                ConnectionMode.ForceOffline -> true
                ConnectionMode.ForceOnline -> false
            }
        val cachedAt =
            when {
                offline && !session.offline -> now
                else -> session.cachedAt
            }
        return session.copy(offline = offline, connectionMode = mode, cachedAt = cachedAt)
    }
}
