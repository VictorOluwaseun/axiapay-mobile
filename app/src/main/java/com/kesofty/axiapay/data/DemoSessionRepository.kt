package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Storage adapter boundary; implementations must replace a snapshot atomically or throw. */
interface DemoSessionStore {
    fun load(): DemoSession?

    fun save(session: DemoSession)
}

/** Serializes demo mutations and persists before publication; contains no payment rules. */
class DemoSessionRepository(private val store: DemoSessionStore, seed: DemoSession) :
    SessionRepository {
    private val lock = Any()
    private val mutableSession = MutableStateFlow(store.load() ?: seed)
    override val session = mutableSession.asStateFlow()

    override fun <T> transact(change: (DemoSession) -> Outcome<SessionChange<T>>): Outcome<T> =
        synchronized(lock) {
            when (val result = change(mutableSession.value)) {
                is Outcome.Failure -> result
                is Outcome.Success -> commit(result.value)
            }
        }

    private fun <T> commit(change: SessionChange<T>): Outcome<T> {
        try {
            store.save(change.session)
        } catch (cause: Exception) {
            return Outcome.Failure(DemoError.Storage(cause))
        }
        mutableSession.value = change.session
        return Outcome.Success(change.value)
    }
}
