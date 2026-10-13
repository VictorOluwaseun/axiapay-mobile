package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.*
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Storage adapter boundary; implementations must replace a snapshot atomically or throw. */
interface DemoSessionStore {
    /** Loads the prior demo snapshot, or null for a new session. */
    fun load(): DemoSession?

    /** Replaces the snapshot atomically; a failure must leave the previous snapshot intact. */
    fun save(session: DemoSession)
}

/**
 * Serializes local demo mutations and saves before publication; contains no payment rules. Copies
 * and seals incoming lists so consumers cannot mutate published state through aliases. The store
 * owns persistence lifetime; no cache, external transactions or backend I/O is used.
 */
class DemoSessionRepository(private val store: DemoSessionStore, seed: DemoSession) :
    SessionRepository {
    private val lock = Any()
    private val mutableSession = MutableStateFlow(snapshotOf(store.load() ?: seed))
    override val session = mutableSession.asStateFlow()

    /** Applies one domain transition under the local lock, following [SessionRepository]. */
    override fun <T> transact(change: (DemoSession) -> Outcome<SessionChange<T>>): Outcome<T> =
        synchronized(lock) {
            when (val result = change(mutableSession.value)) {
                is Outcome.Failure -> result
                is Outcome.Success -> commit(result.value)
            }
        }

    /**
     * Saves an owned snapshot before emission; cancellation is control flow, not a storage error.
     */
    private fun <T> commit(change: SessionChange<T>): Outcome<T> {
        val snapshot = snapshotOf(change.session)
        try {
            store.save(snapshot)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return Outcome.Failure(DemoError.Storage(cause))
        }
        mutableSession.value = snapshot
        return Outcome.Success(change.value)
    }

    /** Owns each list's backing storage; all elements are immutable domain values. */
    private fun snapshotOf(session: DemoSession) =
        session.copy(
            payments = Collections.unmodifiableList(ArrayList(session.payments)),
            plans = Collections.unmodifiableList(ArrayList(session.plans)),
            smsOutbox = Collections.unmodifiableList(ArrayList(session.smsOutbox)),
        )
}
