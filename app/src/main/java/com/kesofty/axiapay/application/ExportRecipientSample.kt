package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*

/** Exposes the selected flavor's download capability without accessing payment or draft state. */
class ExportRecipientSample(private val repository: RecipientSampleRepository) {
    val availableFormats: List<RecipientSampleFormat>
        get() = repository.availableFormats.toList()

    /** Rejects unsupported formats before delegating a user-requested write to the adapter. */
    suspend operator fun invoke(sample: RecipientSample, destination: String): SampleExportResult {
        if (sample.format !in availableFormats) return SampleExportResult.Failed()
        return repository.save(sample, destination)
    }
}
