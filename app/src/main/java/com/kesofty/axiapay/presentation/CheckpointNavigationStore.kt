package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.domain.TextCheckpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Retains immediate form edits and checkpoints the latest immutable input away from the UI thread.
 */
class CheckpointNavigationStore(
    private val checkpoint: TextCheckpoint,
    private val worker: CoroutineDispatcher,
    private val legacySnapshot: String? = null,
) : NavigationStore {
    private val mutex = Mutex()
    @Volatile private var latest: NavigationState? = null
    private var persisted: NavigationState? = null

    /** Reads one owner's navigation; transient picker effects are deliberately excluded. */
    override suspend fun load(): NavigationState? =
        withContext(worker) {
            mutex.withLock {
                val stored = checkpoint.read()
                val restored = (stored ?: legacySnapshot)?.let(NavigationSnapshotCodec::decode)
                latest = restored
                persisted = restored.takeIf { stored != null }
                restored
            }
        }

    /** Called by the presentation owner; no serialization or IO happens on a keystroke. */
    override fun save(state: NavigationState) {
        latest = state
    }

    /** Coalesces queued writes to the newest input while preserving atomic replacement ordering. */
    override suspend fun persist() =
        withContext(worker) {
            mutex.withLock {
                val current = latest ?: return@withLock
                if (current === persisted) return@withLock
                checkpoint.write(NavigationSnapshotCodec.encode(current))
                persisted = current
            }
        }
}
