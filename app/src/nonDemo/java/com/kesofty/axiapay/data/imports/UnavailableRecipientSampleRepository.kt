package com.kesofty.axiapay.data.imports

import com.kesofty.axiapay.domain.*

/** Disables demo sample exports in dev/staging/prod without shipping their bundled assets. */
class UnavailableRecipientSampleRepository : RecipientSampleRepository {
    override val availableFormats = emptyList<RecipientSampleFormat>()

    /** Performs no storage access, including for a stale or manually supplied export command. */
    override suspend fun save(sample: RecipientSample, destination: String): SampleExportResult =
        SampleExportResult.Failed()
}
