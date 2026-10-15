package com.kesofty.axiapay.domain

/** A bounded, local document read; it never uploads recipients or changes payment state. */
fun interface RecipientImportRepository {
    /** Reads a user-granted document reference off the main thread; cancellation propagates. */
    suspend fun read(reference: String): ImportDocumentResult
}

/**
 * Typed cells retain the distinction between text identifiers, numbers and unsupported formulas.
 */
enum class ImportCellKind {
    Text,
    Number,
    Unsupported,
}

/**
 * Raw cell content is untrusted; identifiers must remain text and money is validated separately.
 */
data class ImportCell(val text: String, val kind: ImportCellKind = ImportCellKind.Text)

/** Original one-based row numbers are retained for correcting the source document. */
data class ImportTableRow(val number: Int, val cells: List<ImportCell>)

/**
 * First worksheet only, bounded to 1,000 recipient rows, 16 columns and 256 characters per cell.
 */
data class ImportDocument(
    val fileName: String,
    val sheetName: String?,
    val rows: List<ImportTableRow>,
)

/** Actionable read failures without exposing file contents or provider diagnostics to the UI. */
enum class ImportFileIssue {
    UnsupportedFormat,
    Unreadable,
    InvalidFile,
    TooLarge,
    Encrypted,
    Headers,
    Empty,
}

/** Retains diagnostic cause locally; callers must never log document contents or identifiers. */
sealed interface ImportDocumentResult {
    /** Parsed cells still require application validation before joining a draft. */
    data class Success(val document: ImportDocument) : ImportDocumentResult

    /** No draft mutation occurs on failure. */
    data class Failure(val issue: ImportFileIssue, val cause: Throwable? = null) :
        ImportDocumentResult
}
