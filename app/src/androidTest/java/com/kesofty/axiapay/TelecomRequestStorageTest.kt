package com.kesofty.axiapay

import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.telecom.AtomicTelecomRequestStore
import com.kesofty.axiapay.domain.telecom.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises checked Android journal commits across independent store instances. */
class TelecomRequestStorageTest {
    /** Cold reads remain account/API-scoped, immutable, idempotent and free of financial bodies. */
    @Test
    fun durableReferencesRemainIsolatedAcrossOwnersAndColdInstances() = runBlocking {
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "test-journal-${UUID.randomUUID()}.json",
            )
        try {
            val owner = TelecomOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            val request =
                TelecomRequest(
                    owner,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Instant.now(),
                )
            val store = AtomicTelecomRequestStore(file, Dispatchers.IO)
            store.save(request)
            store.save(request)
            val cold = AtomicTelecomRequestStore(file, Dispatchers.IO)
            assertEquals(listOf(request), cold.read(owner))
            assertTrue(cold.read(owner.copy(accountId = UUID.randomUUID().toString())).isEmpty())
            assertTrue(
                cold.read(owner.copy(apiIdentity = "https://other.example.test/v1/")).isEmpty(),
            )
            try {
                (cold.read(owner) as MutableList).clear()
                fail()
            } catch (_: UnsupportedOperationException) {}
            cold.acknowledge(request)
            cold.acknowledge(request)
            assertTrue(store.read(owner).single().acknowledged)
            val earlier = file.readText()
            try {
                cold.save(request.copy(quoteId = UUID.randomUUID().toString()))
                fail()
            } catch (failure: TelecomFailure) {
                assertEquals(TelecomIssue.Storage, failure.issue)
            }
            assertEquals(earlier, file.readText())
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }

    /** A bounded journal never evicts unfinished requests; acknowledged receipts make safe room. */
    @Test
    fun capacityPrunesOnlyAcknowledgedReferences() = runBlocking {
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "test-journal-${UUID.randomUUID()}.json",
            )
        try {
            val owner = TelecomOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            val records =
                (0 until 200).map {
                    TelecomRequest(
                        owner,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        Instant.now(),
                    )
                }
            val rows = JSONArray()
            records.forEach {
                rows.put(
                    JSONObject()
                        .put("accountId", owner.accountId)
                        .put("apiIdentity", owner.apiIdentity)
                        .put("requestKey", it.requestKey)
                        .put("quoteId", it.quoteId)
                        .put("createdAt", it.createdAt.toString())
                        .put("acknowledged", false),
                )
            }
            file.writeText(JSONObject().put("version", 1).put("requests", rows).toString())
            val store = AtomicTelecomRequestStore(file, Dispatchers.IO)
            val next =
                TelecomRequest(
                    owner,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Instant.now(),
                )
            try {
                store.save(next)
                fail()
            } catch (failure: TelecomFailure) {
                assertEquals(TelecomIssue.Storage, failure.issue)
            }
            assertEquals(records, store.read(owner))
            store.acknowledge(records.first())
            store.save(next)
            assertEquals(records.drop(1) + next, store.read(owner))
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }

    /** Corrupt records cannot silently reset request identities or permit another POST. */
    @Test
    fun corruptionFailsClosedAndPreservesEvidence() = runBlocking {
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "test-journal-${UUID.randomUUID()}.json",
            )
        try {
            file.writeText("damaged recovery journal")
            val store = AtomicTelecomRequestStore(file, Dispatchers.IO)
            val owner = TelecomOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            try {
                store.read(owner)
                fail()
            } catch (failure: TelecomFailure) {
                assertEquals(TelecomIssue.Storage, failure.issue)
            }
            try {
                store.save(
                    TelecomRequest(
                        owner,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        Instant.now(),
                    ),
                )
                fail()
            } catch (failure: TelecomFailure) {
                assertEquals(TelecomIssue.Storage, failure.issue)
            }
            assertEquals("damaged recovery journal", file.readText())
        } finally {
            file.delete()
        }
    }

    /**
     * Version-one airtime references migrate atomically beside data, retaining every original ID.
     */
    @Test
    fun legacyAirtimeJournalRetainsIdentitiesWhenDataIsAdded() = runBlocking {
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "test-migration-${UUID.randomUUID()}.json",
            )
        try {
            val owner = TelecomOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            val old =
                TelecomRequest(
                    owner,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Instant.now(),
                )
            val row =
                JSONObject()
                    .put("accountId", owner.accountId)
                    .put("apiIdentity", owner.apiIdentity)
                    .put("requestKey", old.requestKey)
                    .put("quoteId", old.quoteId)
                    .put("createdAt", old.createdAt.toString())
                    .put("acknowledged", false)
            file.writeText(
                JSONObject().put("version", 1).put("requests", JSONArray().put(row)).toString(),
            )
            val store = AtomicTelecomRequestStore(file, Dispatchers.IO)
            assertEquals(listOf(old), store.read(owner))
            try {
                store.save(old.copy(service = TelecomService.Data))
                fail()
            } catch (failure: TelecomFailure) {
                assertEquals(TelecomIssue.Storage, failure.issue)
            }
            val data =
                old.copy(
                    requestKey = UUID.randomUUID().toString(),
                    quoteId = UUID.randomUUID().toString(),
                    service = TelecomService.Data,
                )
            store.save(data)
            assertEquals(2, JSONObject(file.readText()).getInt("version"))
            assertEquals(
                listOf(old, data),
                AtomicTelecomRequestStore(file, Dispatchers.IO).read(owner),
            )
            store.acknowledge(old)
            assertEquals(listOf(old.copy(acknowledged = true), data), store.read(owner))
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }

    /** Legacy backups win over interrupted base/staged files and retain all original references. */
    @Test
    fun legacyBackupAndInterruptedStagedWritePreserveCommittedReferences() = runBlocking {
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "test-backup-${UUID.randomUUID()}.json",
            )
        val backup = File(file.path + ".bak")
        val staged = File(file.path + ".new")
        try {
            val owner = TelecomOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            val earlier =
                TelecomRequest(
                    owner,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Instant.now(),
                )
            AtomicTelecomRequestStore(file, Dispatchers.IO).save(earlier)
            file.copyTo(backup)
            file.writeText("interrupted legacy base")
            staged.writeText("uncommitted staging bytes")
            val cold = AtomicTelecomRequestStore(file, Dispatchers.IO)
            assertEquals(listOf(earlier), cold.read(owner))
            assertFalse(backup.exists())
            val next =
                earlier.copy(
                    requestKey = UUID.randomUUID().toString(),
                    quoteId = UUID.randomUUID().toString(),
                    service = TelecomService.Data,
                )
            cold.save(next)
            assertEquals(
                listOf(earlier, next),
                AtomicTelecomRequestStore(file, Dispatchers.IO).read(owner),
            )
            assertFalse(staged.exists())
        } finally {
            file.delete()
            backup.delete()
            staged.delete()
        }
    }
}
