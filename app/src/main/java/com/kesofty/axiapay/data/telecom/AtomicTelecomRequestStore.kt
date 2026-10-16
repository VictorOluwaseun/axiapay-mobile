package com.kesofty.axiapay.data.telecom

import android.util.AtomicFile
import com.kesofty.axiapay.domain.telecom.*
import java.io.File
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
 * binding. Atomic replacement preserves earlier requests; corruption/IO failure blocks new POSTs.
 * This is not a payment outbox: nothing here schedules or replays a purchase.
 */
class AtomicTelecomRequestStore(private val file: File, private val io: CoroutineDispatcher) :
    TelecomRequestStore {
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
        if (existing == request) {
            return@storage
        }
        require(existing == null)
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
        if (existing.acknowledged) {
            return@storage
        }
        writeAll(records.map { if (it == request) it.copy(acknowledged = true) else it })
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
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            return emptyList()
        }
        val bytes = ByteArray(262145)
        val size =
            AtomicFile(file).openRead().use { stream ->
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
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (failure: Throwable) {
                atomic.failWrite(stream)
                throw failure
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
