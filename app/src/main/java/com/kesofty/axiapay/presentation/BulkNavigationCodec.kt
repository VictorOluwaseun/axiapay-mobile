package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.application.BulkDraft
import com.kesofty.axiapay.application.BulkRecipientInput
import com.kesofty.axiapay.domain.BulkService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Presentation-only saved state retains unfinished editor input, never jobs or replayable effects.
 */
internal object BulkNavigationCodec {
    /**
     * Excludes derived validation and transient errors; the presenter revalidates on restoration.
     */
    fun encode(state: BulkFlowState): JSONObject =
        JSONObject().apply {
            put("screen", state.screen?.name)
            put("batchId", state.batchId)
            put("filter", state.filter.name)
            put("recipientQuery", state.recipientQuery)
            put("providerPicker", ProviderPickerSnapshotCodec.encode(state.providerPicker))
            put("showErrors", state.showErrors)
            put("editor", state.editor?.let(::recipient))
            put("draft", state.draft?.let(::draft))
            put(
                "savedDrafts",
                JSONArray().apply {
                    state.savedDrafts.forEach { saved ->
                        put(
                            JSONObject().apply {
                                put("draft", draft(saved.draft))
                                put("editor", saved.editor?.let(::recipient))
                            },
                        )
                    }
                },
            )
        }

    /** Missing bulk state keeps older navigation snapshots compatible. */
    fun decode(json: JSONObject?): BulkFlowState {
        if (json == null) return BulkFlowState()
        return BulkFlowState(
            screen =
                json
                    .optString("screen")
                    .takeIf { it.isNotBlank() }
                    ?.let { if (it == "Editor") BulkScreen.Recipients else BulkScreen.valueOf(it) },
            batchId = json.optString("batchId").takeIf { it.isNotBlank() },
            filter = BulkRecipientFilter.valueOf(json.optString("filter", "All")),
            showErrors = json.optBoolean("showErrors"),
            recipientQuery = json.optString("recipientQuery"),
            providerPicker =
                ProviderPickerSnapshotCodec.decode(json.optJSONObject("providerPicker")),
            editor = json.optJSONObject("editor")?.let(::decodeRecipient),
            draft = json.optJSONObject("draft")?.let(::decodeDraft),
            savedDrafts =
                json
                    .optJSONArray("savedDrafts")
                    ?.let { entries ->
                        List(entries.length()) { index ->
                            val value = entries.getJSONObject(index)
                            BulkSavedDraft(
                                decodeDraft(value.getJSONObject("draft")),
                                value.optJSONObject("editor")?.let(::decodeRecipient),
                            )
                        }
                    }
                    .orEmpty(),
        )
    }

    /** Stores only draft input; derived totals and payment execution are deliberately excluded. */
    private fun draft(value: BulkDraft): JSONObject =
        JSONObject().apply {
            put("id", value.id)
            put("title", value.title)
            put("service", value.service.name)
            put("simulateFailure", value.simulateFailure)
            put("recipients", JSONArray().apply { value.recipients.forEach { put(recipient(it)) } })
        }

    /** Older mixed lists retain their terms for the presenter's service-by-service migration. */
    private fun decodeDraft(value: JSONObject): BulkDraft {
        val rows =
            value.getJSONArray("recipients").let { entries ->
                List(entries.length()) { decodeRecipient(entries.getJSONObject(it)) }
            }
        return BulkDraft(
            value.getString("id"),
            value.optString("title"),
            rows,
            value.getBoolean("simulateFailure"),
            value.optString("service").takeIf { it.isNotBlank() }?.let(BulkService::valueOf)
                ?: rows.firstOrNull()?.service
                ?: BulkService.Airtime,
        )
    }

    /** Preserves raw input so an interrupted editor resumes exactly as typed. */
    private fun recipient(value: BulkRecipientInput): JSONObject =
        JSONObject().apply {
            put("id", value.id)
            put("name", value.name)
            put("reference", value.recipientReference)
            put("provider", value.provider)
            put("service", value.service.name)
            put("amount", value.amount)
            put("product", value.product)
        }

    /** Restores raw strings without coercing phone, meter or smartcard identifiers. */
    private fun decodeRecipient(json: JSONObject): BulkRecipientInput =
        BulkRecipientInput(
            json.getString("id"),
            json.getString("name"),
            json.getString("reference"),
            json.getString("provider"),
            BulkService.valueOf(json.getString("service")),
            json.getString("amount"),
            json.getString("product"),
        )
}
