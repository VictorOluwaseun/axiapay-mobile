package com.kesofty.axiapay.presentation

import org.json.JSONObject

/** Shares the small picker snapshot schema across individual and bulk navigation adapters. */
internal object ProviderPickerSnapshotCodec {
    /** Stores display state only; selected payment terms remain in their existing form snapshot. */
    fun encode(state: ProviderPickerState): JSONObject =
        JSONObject().apply {
            put("open", state.isOpen)
            put("query", state.query)
        }

    /** Legacy snapshots default to a closed picker; restored queries stay bounded. */
    fun decode(value: JSONObject?): ProviderPickerState {
        if (value?.optBoolean("open") != true) return ProviderPickerState()
        return ProviderPickerState(isOpen = true, query = value.optString("query").take(80))
    }
}
