package com.kesofty.axiapay

import com.kesofty.axiapay.data.purchases.AtomicPurchaseRequestStore
import com.kesofty.axiapay.data.purchases.PurchaseJournalCommitOperations
import com.kesofty.axiapay.domain.purchases.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Faults at actual journal commit boundaries must block POST while preserving earlier identities.
 */
class PurchaseJournalCommitTest {
    @get:Rule val temporary = TemporaryFolder()

    /** Distinct failure points include a rename that succeeded before directory sync failed. */
    private enum class CommitStage {
        FileSync,
        Replace,
        DirectorySync,
    }

    /**
     * Use real JVM files, fsync and atomic replacement. Directory durability is verified by device
     * tests; this boundary injects failures and records protocol order without Android stubs.
     */
    private class CommitOperations : PurchaseJournalCommitOperations {
        var failure: CommitStage? = null
        val calls = mutableListOf<CommitStage>()

        /** Preserve file contents and surface simulated fsync failure before rename. */
        override fun syncFile(stream: FileOutputStream) {
            reach(CommitStage.FileSync)
            stream.fd.sync()
        }

        /** Replace the real temporary file only after the failure gate permits it. */
        override fun replace(source: File, destination: File) {
            reach(CommitStage.Replace)
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        /** Observe the final barrier, including failure after successful atomic replacement. */
        override fun syncDirectory(directory: File) {
            assertTrue(directory.isDirectory)
            reach(CommitStage.DirectorySync)
        }

        /** An IO failure must remain observable to the repository and its application caller. */
        private fun reach(stage: CommitStage) {
            calls += stage
            if (failure == stage) {
                throw IOException("Injected journal commit failure")
            }
        }
    }

    /** A file fsync failure keeps the prior journal and sends no purchase. */
    @Test
    fun fileSyncFailureBlocksPurchaseAndRetryPreservesIdentity() = runTest {
        exerciseFailure(CommitStage.FileSync)
    }

    /** An atomic rename failure keeps the prior journal and sends no purchase. */
    @Test
    fun renameFailureBlocksPurchaseAndRetryPreservesIdentity() = runTest {
        exerciseFailure(CommitStage.Replace)
    }

    /** Seeing the renamed row on a retry cannot bypass its failed directory durability barrier. */
    @Test
    fun directorySyncFailureBlocksEveryRetryUntilCommitIsConfirmed() = runTest {
        exerciseFailure(CommitStage.DirectorySync)
    }

    /**
     * Exercise the real store and purchase workflow through failure, cold read and explicit retry.
     */
    private suspend fun exerciseFailure(stage: CommitStage) {
        val f = PurchaseFixture()
        val file = temporary.newFolder().resolve("requests.json")
        val commits = CommitOperations()
        val store = AtomicPurchaseRequestStore(file, Dispatchers.IO, commits)
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        val earlier =
            review.request.copy(
                requestKey = UUID.randomUUID().toString(),
                quoteId = UUID.randomUUID().toString(),
            )
        store.save(earlier)
        val useCases = f.createUseCases(requests = store)
        commits.failure = stage
        repeat(2) {
            expectStorage { useCases.submitPurchase(f.accountId, review) }
            assertTrue(f.api.submissions.isEmpty())
            val expected =
                if (stage == CommitStage.DirectorySync) listOf(earlier, review.request)
                else listOf(earlier)
            assertEquals(
                expected,
                AtomicPurchaseRequestStore(file, Dispatchers.IO, CommitOperations())
                    .read(review.request.owner),
            )
            assertFalse(File(file.path + ".new").exists())
        }
        commits.failure = null
        commits.calls.clear()
        assertEquals(
            PurchasePaymentStatus.Pending,
            useCases.submitPurchase(f.accountId, review).payment?.status,
        )
        assertEquals(
            listOf(CommitStage.FileSync, CommitStage.Replace, CommitStage.DirectorySync),
            commits.calls,
        )
        assertEquals(listOf(review.request), f.api.submissions)
        assertEquals(listOf(earlier, review.request), store.read(review.request.owner))
    }

    /** A cold retry keeps the same key and waits for a successful directory sync before sending. */
    @Test
    fun coldRetryOfAReadableRequestRequiresDurableStorage() = runTest {
        val f = PurchaseFixture()
        val file = temporary.newFolder().resolve("requests.json")
        val commits = CommitOperations()
        val firstStore = AtomicPurchaseRequestStore(file, Dispatchers.IO, commits)
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        commits.failure = CommitStage.DirectorySync
        expectStorage {
            f.createUseCases(requests = firstStore).submitPurchase(f.accountId, review)
        }

        val restoredStore = AtomicPurchaseRequestStore(file, Dispatchers.IO, commits)
        val saved = restoredStore.read(review.request.owner).single()
        assertEquals(review.request, saved)
        val submitPurchase = f.createUseCases(requests = restoredStore).submitPurchase
        expectStorage { submitPurchase.retry(f.accountId, saved) }
        assertTrue(f.api.submissions.isEmpty())

        commits.failure = null
        assertNotNull(submitPurchase.retry(f.accountId, saved).payment)
        assertEquals(listOf(review.request), f.api.submissions)
        assertEquals(1, f.api.quoteCalls)
    }

    /**
     * Local acknowledgement also requires a durable commit on every retry after an uncertain sync.
     */
    @Test
    fun acknowledgementFailureRetainsReferencesAndRequiresCheckedRetry() = runTest {
        val f = PurchaseFixture()
        val file = temporary.newFolder().resolve("requests.json")
        val commits = CommitOperations()
        val store = AtomicPurchaseRequestStore(file, Dispatchers.IO, commits)
        val useCases = f.createUseCases(requests = store)
        val review = useCases.reviewPurchase(f.accountId, f.input)
        f.api.status = PurchasePaymentStatus.Succeeded
        val progress = useCases.submitPurchase(f.accountId, review)
        commits.failure = CommitStage.DirectorySync
        repeat(2) {
            expectStorage { useCases.payments.acknowledge(progress) }
            assertEquals(
                review.request.requestKey,
                store.read(review.request.owner).single().requestKey,
            )
        }
        commits.failure = null
        useCases.payments.acknowledge(progress)
        assertTrue(store.read(review.request.owner).single().acknowledged)
        assertEquals(1, f.api.submissions.size)
    }

    /**
     * Legacy backup recovery never silently reads an interrupted base after a failed replacement.
     */
    @Test
    fun legacyBackupRecoveryPropagatesCommitFailures() = runTest {
        val f = PurchaseFixture()
        val file = temporary.newFolder().resolve("requests.json")
        val commits = CommitOperations()
        val store = AtomicPurchaseRequestStore(file, Dispatchers.IO, commits)
        val request = f.useCases.reviewPurchase(f.accountId, f.input).request
        store.save(request)
        val backup = File(file.path + ".bak")
        Files.move(file.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        file.writeText("interrupted legacy write")
        commits.failure = CommitStage.Replace
        expectStorage { store.read(request.owner) }
        assertTrue(backup.exists())
        assertEquals("interrupted legacy write", file.readText())
        commits.failure = CommitStage.DirectorySync
        expectStorage { store.read(request.owner) }
        commits.failure = null
        assertEquals(listOf(request), store.read(request.owner))
        assertFalse(backup.exists())
    }

    /** A failed storage barrier must retain its classification instead of permitting a mutation. */
    private suspend fun expectStorage(operation: suspend () -> Any?) {
        try {
            operation()
            fail("Expected storage failure")
        } catch (failure: PurchaseFailure) {
            assertEquals(PurchaseIssue.Storage, failure.issue)
        }
    }
}
