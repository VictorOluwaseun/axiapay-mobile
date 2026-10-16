package com.kesofty.axiapay

import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.airtime.AtomicAirtimeRequestStore
import com.kesofty.axiapay.domain.airtime.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual Android AtomicFile persistence across independent journal instances. */
class AirtimeRequestStorageTest {
    /** Cold reads remain account/API-scoped, immutable, idempotent and free of financial bodies. */
    @Test
    fun durableReferencesRemainIsolatedAcrossOwnersAndColdInstances() = runBlocking {
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "test-journal-${UUID.randomUUID()}.json",
            )
        try {
            val owner = AirtimeOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            val request =
                AirtimeRequest(
                    owner,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Instant.now(),
                )
            val store = AtomicAirtimeRequestStore(file, Dispatchers.IO)
            store.save(request)
            store.save(request)
            val cold = AtomicAirtimeRequestStore(file, Dispatchers.IO)
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
            } catch (failure: AirtimeFailure) {
                assertEquals(AirtimeIssue.Storage, failure.issue)
            }
            assertEquals(earlier, file.readText())
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
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
            val owner = AirtimeOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            val records =
                (0 until 200).map {
                    AirtimeRequest(
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
            val store = AtomicAirtimeRequestStore(file, Dispatchers.IO)
            val next =
                AirtimeRequest(
                    owner,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Instant.now(),
                )
            try {
                store.save(next)
                fail()
            } catch (failure: AirtimeFailure) {
                assertEquals(AirtimeIssue.Storage, failure.issue)
            }
            assertEquals(records, store.read(owner))
            store.acknowledge(records.first())
            store.save(next)
            assertEquals(records.drop(1) + next, store.read(owner))
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
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
            val store = AtomicAirtimeRequestStore(file, Dispatchers.IO)
            val owner = AirtimeOwner(UUID.randomUUID().toString(), "https://api.example.test/v1/")
            try {
                store.read(owner)
                fail()
            } catch (failure: AirtimeFailure) {
                assertEquals(AirtimeIssue.Storage, failure.issue)
            }
            try {
                store.save(
                    AirtimeRequest(
                        owner,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        Instant.now(),
                    ),
                )
                fail()
            } catch (failure: AirtimeFailure) {
                assertEquals(AirtimeIssue.Storage, failure.issue)
            }
            assertEquals("damaged recovery journal", file.readText())
        } finally {
            file.delete()
        }
    }
}
