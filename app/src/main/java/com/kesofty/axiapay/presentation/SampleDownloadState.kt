package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.domain.RecipientSample
import com.kesofty.axiapay.domain.RecipientSampleFormat
import com.kesofty.axiapay.domain.SampleExportResult

/** Picker effects are acknowledged before launch; saving requires its matching one-time result. */
enum class SampleDownloadPhase {
    LaunchPicker,
    ChoosingLocation,
    Saving,
    Saved,
    Failed,
}

/** Explicit download commands distinguish choosing a location, cancellation and file completion. */
sealed interface SampleDownloadIntent {
    /** Starts a fresh export for the currently selected service, if supported by this flavor. */
    data class Request(val format: RecipientSampleFormat, val recipientCount: Int = 3) :
        SampleDownloadIntent

    /** Consumes the picker effect before Android launches it, preventing recreation replay. */
    data class PickerOpened(val requestId: String) : SampleDownloadIntent

    /** A null destination cancels quietly; stale and repeated callbacks are ignored. */
    data class Destination(val requestId: String, val reference: String?) : SampleDownloadIntent

    /** Makes a missing document app recoverable without affecting the draft or import preview. */
    data class PickerUnavailable(val requestId: String) : SampleDownloadIntent
}

/** An ephemeral request binds the destination to its original service and import session. */
data class SampleDownloadState(
    val id: String,
    val sample: RecipientSample,
    val phase: SampleDownloadPhase = SampleDownloadPhase.LaunchPicker,
    val failure: SampleExportResult.Failed? = null,
) {
    val busy: Boolean
        get() =
            phase in
                setOf(
                    SampleDownloadPhase.LaunchPicker,
                    SampleDownloadPhase.ChoosingLocation,
                    SampleDownloadPhase.Saving,
                )
}
