package com.kesofty.axiapay.domain

/** File formats offered by the flavor's import-sample capability, not payment types. */
enum class RecipientSampleFormat(val extension: String, val mimeType: String) {
    Csv("csv", "text/csv"),
    Excel("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

/** Identifies a bundled synthetic example independently of Android assets or document URIs. */
data class RecipientSample(
    val service: BulkService,
    val format: RecipientSampleFormat,
    val recipientCount: Int = 3,
) {
    init {
        require(
            recipientCount == 3 ||
                recipientCount == BulkLimits.MAX_RECIPIENTS && format == RecipientSampleFormat.Csv,
        )
    }

    val fileName: String
        get() =
            "axiapay-${service.name.lowercase()}-sample${if (recipientCount == 3) "" else "-$recipientCount"}.${format.extension}"
}

/** Export completion describes the file only; it never indicates a payment or import result. */
sealed interface SampleExportResult {
    /** All file bytes were written and the output stream closed successfully. */
    data object Saved : SampleExportResult

    /**
     * Preserves the original failure for controlled diagnostics without displaying file details.
     */
    data class Failed(val cause: Throwable? = null) : SampleExportResult
}

/**
 * Supplies flavor-specific samples to a newly created, user-granted destination. Implementations
 * are main-safe, propagate cancellation, and own no cache, draft, wallet or transaction.
 */
interface RecipientSampleRepository {
    val availableFormats: List<RecipientSampleFormat>

    /** Writes one synthetic sample; the caller must obtain a fresh destination for each attempt. */
    suspend fun save(sample: RecipientSample, destination: String): SampleExportResult
}
