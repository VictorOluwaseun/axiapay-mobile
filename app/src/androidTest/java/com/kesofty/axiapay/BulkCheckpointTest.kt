package com.kesofty.axiapay

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises Android's real atomic files and codecs with full-size drafts and reserved payments. */
class BulkCheckpointTest {
    /**
     * Checkpoints survive new repository instances; pending work is never automatically executed.
     */
    @Test
    fun largeDraftAndReservedBatchRestoreOutsideBundle() = runBlocking {
        val directory = testDirectory()
        try {
            val handle = SavedStateHandle()
            val sessionFile = File(directory, "session.json")
            val navigationFile = File(directory, "navigation.json")
            handle["checkpoint.session.v1"] = sessionFile.name
            handle["checkpoint.navigation.v1"] = navigationFile.name
            val checkpoint = AtomicTextCheckpoint(sessionFile, Dispatchers.IO)
            val repository =
                CheckpointSessionRepository(checkpoint, DemoSession(20_000_000), Dispatchers.IO)
            assertTrue(repository.initialize() is Outcome.Success)
            val clock = Clock.systemUTC()
            val prepare = PrepareBulkBatch(PreparePayment(clock), clock)
            val draft = draft()
            assertTrue(QueueDemoBulkBatch(repository, prepare)(draft) is Outcome.Success)
            repository.transact { DemoBulkPolicy.beginNextGroup(it, draft.id, 25) }
            val navigation =
                CheckpointNavigationStore(
                    AtomicTextCheckpoint(navigationFile, Dispatchers.IO),
                    Dispatchers.IO,
                )
            val flow =
                BulkFlowState(
                    screen = BulkScreen.Recipients,
                    draft = draft.copy(id = "next-draft"),
                    editor = draft.recipients.last().copy(amount = "125.25"),
                    batchId = draft.id,
                )
            navigation.save(NavigationState(bulk = flow))
            navigation.persist()
            val restored =
                CheckpointSessionRepository(
                    AtomicTextCheckpoint(sessionFile, Dispatchers.IO, required = true),
                    DemoSession(0),
                    Dispatchers.IO,
                )
            assertTrue(restored.initialize() is Outcome.Success)
            assertEquals(repository.session.value, restored.session.value)
            assertEquals(
                25,
                restored.session.value.batches.single().recipients.count {
                    it.status == BulkRecipientStatus.Processing
                },
            )
            assertTrue(restored.session.value.payments.isEmpty())
            val restoredNavigation =
                CheckpointNavigationStore(
                        AtomicTextCheckpoint(navigationFile, Dispatchers.IO, required = true),
                        Dispatchers.IO,
                    )
                    .load()!!
            assertEquals(flow.draft, restoredNavigation.bulk.draft)
            assertEquals(flow.editor, restoredNavigation.bulk.editor)
            val bundle =
                Bundle().apply { handle.keys().forEach { putString(it, handle.get<String>(it)) } }
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle)
                assertTrue(
                    "Saved state must contain only small checkpoint references",
                    parcel.dataSize() < 1024,
                )
            } finally {
                parcel.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * A failing atomic replacement preserves both the previously saved wallet and published state.
     */
    @Test
    fun failedWritePublishesNothingAndCorruptReadNeverResetsToSeed() = runBlocking {
        val directory = testDirectory()
        try {
            val file = File(directory, "session.json")
            val disk = AtomicTextCheckpoint(file, Dispatchers.IO)
            var fail = false
            val checkpoint =
                object : TextCheckpoint {
                    override suspend fun read() = disk.read()

                    override suspend fun write(value: String) {
                        if (fail) throw java.io.IOException("Synthetic write failure")
                        disk.write(value)
                    }
                }
            val repository =
                CheckpointSessionRepository(checkpoint, DemoSession(20_000_000), Dispatchers.IO)
            repository.initialize()
            val before = repository.session.value
            fail = true
            assertTrue(
                repository.transact { Outcome.Success(SessionChange(it.copy(balance = 0), Unit)) }
                    is Outcome.Failure,
            )
            assertEquals(before, repository.session.value)
            val restored = CheckpointSessionRepository(disk, DemoSession(0), Dispatchers.IO)
            restored.initialize()
            assertEquals(before, restored.session.value)
            disk.write("broken checkpoint")
            val broken = CheckpointSessionRepository(disk, DemoSession(99), Dispatchers.IO)
            assertTrue(broken.initialize() is Outcome.Failure)
            assertEquals("broken checkpoint", disk.read())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Cancellation cannot leave disk committed while the same owner's published wallet is stale.
     */
    @Test
    fun cancellationDuringCommitKeepsDiskAndMemoryInAgreement() = runBlocking {
        val directory = testDirectory()
        try {
            val disk = AtomicTextCheckpoint(File(directory, "session.json"), Dispatchers.IO)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var pause = false
            val checkpoint =
                object : TextCheckpoint {
                    override suspend fun read() = disk.read()

                    override suspend fun write(value: String) {
                        disk.write(value)
                        if (!pause) return
                        reached.complete(Unit)
                        release.await()
                    }
                }
            val repository =
                CheckpointSessionRepository(checkpoint, DemoSession(20_000_000), Dispatchers.IO)
            repository.initialize()
            pause = true
            val job =
                launch(Dispatchers.Default) {
                    repository.transact {
                        Outcome.Success(SessionChange(it.copy(hidden = true), Unit))
                    }
                }
            reached.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            val restored = CheckpointSessionRepository(disk, DemoSession(0), Dispatchers.IO)
            restored.initialize()
            assertTrue(repository.session.value.hidden)
            assertEquals(repository.session.value, restored.session.value)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Allocates isolated test storage without touching the user's current demo checkpoints. */
    private fun testDirectory(): File =
        File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "bulk-check-${UUID.randomUUID()}",
            )
            .apply { mkdirs() }

    /** Builds exactly 1,000 affordable synthetic airtime recipients. */
    private fun draft() =
        BulkDraft(
            "checkpoint-batch",
            recipients =
                (1..1000).map {
                    BulkRecipientInput(
                        "row-$it",
                        "Demo $it",
                        "0805${it.toString().padStart(7, '0')}",
                        amount = "100",
                    )
                },
        )
}
