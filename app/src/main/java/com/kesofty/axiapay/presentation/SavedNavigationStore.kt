package com.kesofty.axiapay.presentation

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.Recurrence
import java.time.LocalDate
import org.json.JSONObject

/** Legacy saved-state adapter retained for migration and small snapshot compatibility checks. */
class SavedNavigationStore(private val handle: SavedStateHandle) : NavigationStore {
    override fun save(state: NavigationState) {
        handle[KEY] = NavigationSnapshotCodec.encode(state)
    }

    override suspend fun load(): NavigationState? =
        handle.get<String>(KEY)?.let(NavigationSnapshotCodec::decode)

    private companion object {
        const val KEY = "presentation.navigation.v1"
    }
}

/** Private checkpoint encoding omits import effects and derived list/validation state. */
internal object NavigationSnapshotCodec {
    /** Serializes durable navigation input only; restored screens cannot replay commands. */
    fun encode(state: NavigationState): String =
        JSONObject()
            .apply {
                put("bulkFlow", BulkNavigationCodec.encode(state.bulk))
                put("tab", state.tab)
                put("query", state.serviceQuery)
                put("filter", state.activityFilter)
                put("sheet", encodeSheet(state.sheet))
                put("draft", state.draft?.let(::encodeForm))
            }
            .toString()

    /** Restores the retained input; the presenter subsequently revalidates all terms. */
    fun decode(value: String): NavigationState {
        val json = JSONObject(value)
        return NavigationState(
            json.getInt("tab"),
            decodeSheet(json.getJSONObject("sheet")),
            json.getString("query"),
            json.getString("filter"),
            json.optJSONObject("draft")?.let(::decodeForm),
            BulkNavigationCodec.decode(json.optJSONObject("bulkFlow")),
        )
    }

    private fun encodeSheet(sheet: Sheet?): JSONObject =
        when (sheet) {
            null -> JSONObject().put("type", "none")
            Sheet.Notifications -> JSONObject().put("type", "notifications")
            Sheet.Profile -> JSONObject().put("type", "profile")
            Sheet.Offline -> JSONObject().put("type", "offline")
            is Sheet.Explore -> JSONObject().put("type", "explore").put("service", sheet.service)
            is Sheet.Sms -> JSONObject().put("type", "sms").put("id", sheet.requestId)
            is Sheet.Receipt -> JSONObject().put("type", "receipt").put("id", sheet.paymentId)
            is Sheet.Payment -> encodeForm(sheet.form).put("type", "payment")
        }

    private fun decodeSheet(json: JSONObject): Sheet? =
        when (json.getString("type")) {
            "none" -> null
            "notifications" -> Sheet.Notifications
            "profile" -> Sheet.Profile
            "bulk" -> null // The retired sample sheet returns to the preserved Payments tab.
            "offline" -> Sheet.Offline
            "explore" -> Sheet.Explore(json.getString("service"))
            "sms" -> Sheet.Sms(json.getString("id"))
            "receipt" -> Sheet.Receipt(json.getString("id"))
            "payment" -> Sheet.Payment(decodeForm(json))
            else -> error("Unknown saved presentation state")
        }

    private fun encodeForm(form: PaymentFormState) =
        JSONObject().apply {
            val input = form.input
            put("id", form.requestId)
            put("route", form.route)
            put("reviewing", form.reviewing)
            put("providerPicker", ProviderPickerSnapshotCodec.encode(form.providerPicker))
            put("sim", form.smsSim)
            put("service", input.type.name)
            put("provider", input.provider)
            put("recipient", input.recipient)
            put("amount", input.amount)
            put("destination", input.destination.name)
            put("plan", input.dataPlan)
            put("scheduled", input.scheduled)
            put("date", input.date.toString())
            put("recurrence", input.recurrence.name)
        }

    private fun decodeForm(json: JSONObject) =
        PaymentFormState(
            json.getString("id"),
            json.getString("route"),
            PaymentInput(
                PaymentType.valueOf(json.getString("service")),
                json.getString("provider"),
                json.getString("recipient"),
                json.getString("amount"),
                TransferDestination.valueOf(json.getString("destination")),
                json.getString("plan"),
                json.getBoolean("scheduled"),
                LocalDate.parse(json.getString("date")),
                Recurrence.valueOf(json.getString("recurrence")),
            ),
            json.getBoolean("reviewing"),
            smsSim = json.optInt("sim", 1),
            providerPicker =
                ProviderPickerSnapshotCodec.decode(json.optJSONObject("providerPicker")),
        )
}
