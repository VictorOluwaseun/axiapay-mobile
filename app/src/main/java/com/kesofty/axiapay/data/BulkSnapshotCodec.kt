package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Private saved-state format for batch terms and progress; no execution occurs on decode. */
internal object BulkSnapshotCodec {
    /** Serializes stable recipient identities, attempts and known outcomes. */
    fun encode(batch: BulkBatch): JSONObject =
        JSONObject().apply {
            put("id", batch.id)
            put("title", batch.title)
            put("created", batch.createdAt.toString())
            put("simulateFailure", batch.simulateFailure)
            put("recipients", JSONArray().apply { batch.recipients.forEach { put(recipient(it)) } })
        }

    /** Restores queued/processing rows exactly; only an explicit resume can advance them. */
    fun decode(json: JSONObject): BulkBatch =
        BulkBatch(
            json.getString("id"),
            json.getString("title"),
            json.getJSONArray("recipients").let { rows ->
                List(rows.length()) { decodeRecipient(rows.getJSONObject(it)) }
            },
            Instant.parse(json.getString("created")),
            json.getBoolean("simulateFailure"),
        )

    /** Stores exact minor units and treats account identifiers as strings. */
    private fun recipient(value: BulkRecipient): JSONObject =
        JSONObject().apply {
            val details = value.details
            put("id", details.id)
            put("name", details.name)
            put("reference", details.recipientReference)
            put("provider", details.provider)
            put("service", details.service.name)
            put("amount", details.amountMinorUnits)
            put("product", details.product)
            put("status", value.status.name)
            put("attempts", value.attempts)
        }

    /** Reconstructs immutable domain values without resolving live provider information. */
    private fun decodeRecipient(json: JSONObject): BulkRecipient =
        BulkRecipient(
            BulkRecipientDetails(
                json.getString("id"),
                json.getString("name"),
                json.getString("reference"),
                json.getString("provider"),
                BulkService.valueOf(json.getString("service")),
                json.getLong("amount"),
                json.getString("product"),
            ),
            BulkRecipientStatus.valueOf(json.getString("status")),
            json.getInt("attempts"),
        )
}
