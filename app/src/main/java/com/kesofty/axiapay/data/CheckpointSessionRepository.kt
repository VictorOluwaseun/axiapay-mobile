package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns one demo session's disk checkpoint. Wallet, receipts, pending rows and their reservations
 * commit in the same atomic file before publication. No cache, network ledger or shared singleton.
 */
class CheckpointSessionRepository(
    private val checkpoint: TextCheckpoint,
    seed: DemoSession,
    private val worker: CoroutineDispatcher,
    private val legacySnapshot: String? = null,
) : SessionRepository {
    private val mutex = Mutex()
    private val mutableSession = MutableStateFlow(seed)
    override val session = mutableSession.asStateFlow()
    private var initialized = false

    /**
     * Restores once; corruption stays actionable and never silently resets a wallet to demo data.
     */
    override suspend fun initialize(): Outcome<Unit> =
        withContext(worker) {
            mutex.withLock {
                if (initialized) return@withLock Outcome.Success(Unit)
                try {
                    val stored = checkpoint.read() ?: legacySnapshot
                    val restored = stored?.let(SessionSnapshotCodec::decode) ?: mutableSession.value
                    val snapshot = ownedSnapshot(restored)
                    withContext(NonCancellable) {
                        checkpoint.write(SessionSnapshotCodec.encode(snapshot))
                        mutableSession.value = snapshot
                        initialized = true
                    }
                    Outcome.Success(Unit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Outcome.Failure(DemoError.Storage(failure))
                }
            }
        }

    /**
     * Runs each transition once under the owner's mutex and persists before emitting its result.
     */
    override suspend fun <T> transact(
        change: (DemoSession) -> Outcome<SessionChange<T>>,
    ): Outcome<T> =
        withContext(worker) {
            mutex.withLock {
                if (!initialized)
                    return@withLock Outcome.Failure(
                        DemoError.Storage(IllegalStateException("Session is not restored")),
                    )
                when (val result = change(mutableSession.value)) {
                    is Outcome.Failure -> result
                    is Outcome.Success -> commit(result.value)
                }
            }
        }

    /**
     * No-op transitions do not rewrite the large checkpoint; failed persistence publishes nothing.
     */
    private suspend fun <T> commit(change: SessionChange<T>): Outcome<T> {
        if (change.session === mutableSession.value) return Outcome.Success(change.value)
        try {
            val snapshot = ownedSnapshot(change.session)
            withContext(NonCancellable) {
                checkpoint.write(SessionSnapshotCodec.encode(snapshot))
                mutableSession.value = snapshot
            }
            return Outcome.Success(change.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Outcome.Failure(DemoError.Storage(failure))
        }
    }

    /**
     * Seals every nested collection to prevent callers changing a committed checkpoint by alias.
     */
    private fun ownedSnapshot(session: DemoSession): DemoSession =
        session.copy(
            payments = java.util.Collections.unmodifiableList(ArrayList(session.payments)),
            plans = java.util.Collections.unmodifiableList(ArrayList(session.plans)),
            smsOutbox = java.util.Collections.unmodifiableList(ArrayList(session.smsOutbox)),
            batches =
                java.util.Collections.unmodifiableList(
                    session.batches.map {
                        it.copy(
                            recipients =
                                java.util.Collections.unmodifiableList(ArrayList(it.recipients)),
                        )
                    },
                ),
        )
}
