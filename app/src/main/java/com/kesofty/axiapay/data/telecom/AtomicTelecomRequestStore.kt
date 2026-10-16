package com.kesofty.axiapay.data.telecom

import com.kesofty.axiapay.domain.telecom.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private no-backup recovery references, shared across owners through a singleton composition
 * binding. Checked file sync, atomic replacement and directory sync preserve recovery references;
 * corruption/IO/commit failure blocks new POSTs. The app supplies an existing no-backup directory.
 * This is not a payment outbox: nothing here schedules or replays a purchase.
 */
class AtomicTelecomRequestStore
internal constructor(
    private val file: File,
    private val io: CoroutineDispatcher,
    private val commits: TelecomJournalCommitOperations,
) : TelecomRequestStore {
    /**
     * The app owns an existing no-backup parent directory; every commit error remains observable.
     */
    constructor(
        file: File,
        io: CoroutineDispatcher,
    ) : this(file, io, AndroidTelecomJournalCommitOperations)

    private val mutex = Mutex()

    /** Read only this verified account/API scope and never expose a mutable backing collection. */
    override suspend fun read(owner: TelecomOwner): List<TelecomRequest> = storage {
        Collections.unmodifiableList(readAll().filter { it.owner == owner })
    }

    /**
     * Keep the original quote/key immutable and commit it durably before its network submission.
     */
    override suspend fun save(request: TelecomRequest) = storage {
        val records = readAll()
        val existing = records.find {
            it.owner == request.owner && it.requestKey == request.requestKey
        }
        require(existing == null || existing == request)
        if (existing == request) {
            // A previous rename may have succeeded before directory sync failed. Reconfirm
            // durability.
            writeAll(records)
            return@storage
        }
        val oldestAcknowledged = records.firstOrNull { it.acknowledged }
        val retained =
            when {
                records.size < 200 -> records
                oldestAcknowledged != null -> records.filterNot { it == oldestAcknowledged }
                else -> error("Recovery journal full")
            }
        writeAll(retained + request)
    }

    /**
     * Retain receipt references after acknowledgement; only acknowledged entries may be pruned at
     * capacity.
     */
    override suspend fun acknowledge(request: TelecomRequest) = storage {
        val records = readAll()
        val existing =
            records.find {
                it.owner == request.owner && it.requestKey == request.requestKey
            } ?: error("Missing receipt reference")
        require(existing.copy(acknowledged = false) == request.copy(acknowledged = false))
        writeAll(records.map { if (it == existing) it.copy(acknowledged = true) else it })
    }

    /** Serialize IO on the owning dispatcher and preserve cancellation/failure context. */
    private suspend fun <T> storage(operation: suspend () -> T): T = mutex.withLock {
        try {
            withContext(io) { operation() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TelecomFailure) {
            throw failure
        } catch (failure: Exception) {
            throw TelecomFailure(TelecomIssue.Storage, failure)
        }
    }

    /**
     * Bound actual bytes before parsing; unsupported versions or damaged records never silently
     * reset recovery.
     */
    private fun readAll(): List<TelecomRequest> {
        val stream = openCommitted() ?: return emptyList()
        val bytes = ByteArray(262145)
        val size = stream.use {
            var count = 0
            while (count < bytes.size) {
                val read = stream.read(bytes, count, bytes.size - count)
                if (read == -1) {
                    break
                }
                count += read
            }
            count
        }
        require(size <= 262144)
        val json = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
        val version = json.get("version")
        require(version == 1 || version == 2)
        val array = json.getJSONArray("requests")
        require(array.length() <= 200)
        val records =
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                val api = text(row, "apiIdentity")
                require(api.length in 1..4096)
                TelecomRequest(
                    TelecomOwner(id(row, "accountId"), api),
                    id(row, "requestKey"),
                    id(row, "quoteId"),
                    Instant.parse(text(row, "createdAt")),
                    row.get("acknowledged") as? Boolean ?: error("Expected boolean"),
                    storedService(row, version == 1),
                )
            }
        require(records.distinctBy { it.owner to it.requestKey }.size == records.size)
        return records
    }

    /**
     * Finish a small atomic commit despite cancellation; only IDs, scope and creation time enter
     * storage.
     */
    private suspend fun writeAll(records: List<TelecomRequest>) =
        withContext(NonCancellable + io) {
            val rows = JSONArray()
            records.forEach { request ->
                rows.put(
                    JSONObject()
                        .put("accountId", request.owner.accountId)
                        .put("apiIdentity", request.owner.apiIdentity)
                        .put("requestKey", request.requestKey)
                        .put("quoteId", request.quoteId)
                        .put("createdAt", request.createdAt.toString())
                        .put("acknowledged", request.acknowledged)
                        .put("serviceType", request.service.name.lowercase(Locale.ROOT)),
                )
            }
            val bytes =
                JSONObject()
                    .put("version", 2)
                    .put("requests", rows)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            require(bytes.size <= 262144)
            val directory = requireNotNull(file.parentFile)
            check(directory.isDirectory)
            val staged = File(file.path + ".new")
            try {
                FileOutputStream(staged).use { stream ->
                    stream.write(bytes)
                    commits.syncFile(stream)
                }
                commits.replace(staged, file)
                commits.syncDirectory(directory)
            } catch (failure: Throwable) {
                try {
                    Files.deleteIfExists(staged.toPath())
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

    /**
     * Restore legacy AtomicFile backups with checked replacement. Only a genuinely absent file is
     * empty; permission, recovery and read errors must block a new purchase. Staged .new files were
     * never committed and never become authoritative on restart.
     */
    private fun openCommitted(): InputStream? {
        val backup = File(file.path + ".bak")
        val backupExists =
            try {
                Files.readAttributes(
                    backup.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                )
                true
            } catch (_: NoSuchFileException) {
                false
            }
        if (backupExists) {
            commits.replace(backup, file)
            commits.syncDirectory(requireNotNull(file.parentFile))
        }
        return try {
            Files.newInputStream(file.toPath())
        } catch (_: NoSuchFileException) {
            null
        }
    }

    /** Legacy version-one rows are airtime; migration never changes accepted quote/request IDs. */
    private fun storedService(row: JSONObject, legacy: Boolean): TelecomService {
        if (legacy) {
            return TelecomService.Airtime
        }
        return when (text(row, "serviceType")) {
            "airtime" -> TelecomService.Airtime
            "data" -> TelecomService.Data
            else -> error("Unsupported saved service")
        }
    }

    /** Reject implicit credential/reference conversion in restored local records. */
    private fun text(json: JSONObject, name: String): String =
        json.get(name) as? String ?: error("Expected text")

    /** Locally generated UUIDs must retain their full canonical shape after restoration. */
    private fun id(json: JSONObject, name: String): String =
        text(json, name).also {
            require(UUID.fromString(it).toString() == it.lowercase(Locale.ROOT))
        }
}
