package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Import-specific issues complement the existing single-payment validation codes. */
enum class ImportRowIssue {
    NumericReference,
    UnsupportedCell,
    Duplicate,
    ServiceMismatch,
    PriceMismatch,
    NameTooLong,
    ExtraCells,
}

/** A source row retains its correction location; only rows without either issue are selectable. */
data class RecipientImportRow(
    val number: Int,
    val input: BulkRecipientInput,
    val issue: ImportRowIssue? = null,
    val paymentIssue: BulkInputIssue? = null,
    val amountMinorUnits: Long? = null,
) {
    val valid: Boolean
        get() = issue == null && paymentIssue == null
}

/** Validation is a preview only; no wallet, payment, draft or source document is mutated. */
data class RecipientImportPreview(
    val fileName: String,
    val sheetName: String?,
    val rows: List<RecipientImportRow>,
    val availableSlots: Int,
)

/** Separates document failures from row-level corrections so valid rows can still be selected. */
sealed interface RecipientImportResult {
    /** Ready for explicit selection, with all skipped rows visible. */
    data class Ready(val preview: RecipientImportPreview) : RecipientImportResult

    /** Includes the original read failure for controlled diagnostics, never UI file contents. */
    data class Failed(val failure: ImportDocumentResult.Failure) : RecipientImportResult
}

/** Prepares and revalidates imported recipients against the active service and existing draft. */
class PrepareRecipientImport(
    private val repository: RecipientImportRepository,
    private val prepare: PrepareBulkBatch,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> String,
) {
    /** Reads via the main-safe repository, then validates without changing the current editor. */
    suspend operator fun invoke(
        reference: String,
        draft: BulkDraft,
        editor: BulkRecipientInput?,
    ): RecipientImportResult =
        when (val result = repository.read(reference)) {
            is ImportDocumentResult.Failure -> RecipientImportResult.Failed(result)
            is ImportDocumentResult.Success ->
                withContext(worker) { preview(result.document, draft, editor) }
        }

    /**
     * Maps recognized headers case-insensitively; ambiguous or missing required columns fail
     * clearly.
     */
    fun preview(
        document: ImportDocument,
        draft: BulkDraft,
        editor: BulkRecipientInput?,
    ): RecipientImportResult {
        val header = document.rows.firstOrNull() ?: return failure(ImportFileIssue.Empty)
        val columns = header.cells.map { headerName(it.text) }
        val nonempty = columns.filter { it.isNotBlank() }
        val required =
            when (draft.service) {
                BulkService.Airtime -> setOf("recipient", "provider", "amount")
                BulkService.Data,
                BulkService.TV -> setOf("recipient", "provider", "product")
                BulkService.Electricity -> setOf("recipient", "provider", "product", "amount")
            }
        if (
            !columns.containsAll(required) ||
                nonempty.distinct().size != nonempty.size ||
                header.cells.any { it.kind != ImportCellKind.Text }
        )
            return failure(ImportFileIssue.Headers)
        val existing =
            effectiveRecipients(draft, editor).map(prepare::recipientIdentity).toMutableSet()
        val rows =
            document.rows.drop(1).map { source ->
                val row = validateRow(source, columns, draft, existing)
                if (row.valid) existing += prepare.recipientIdentity(row.input)
                row
            }
        if (rows.isEmpty()) return failure(ImportFileIssue.Empty)
        return RecipientImportResult.Ready(
            RecipientImportPreview(
                document.fileName,
                document.sheetName,
                rows,
                availableSlots(draft, editor),
            ),
        )
    }

    /**
     * Rechecks selected terms and capacity atomically; a stale preview cannot overwrite newer
     * input.
     */
    fun append(
        preview: RecipientImportPreview,
        selectedIds: Set<String>,
        draft: BulkDraft,
        editor: BulkRecipientInput?,
    ): BulkDraft? {
        val chosen = preview.rows.filter { it.input.id in selectedIds }
        if (chosen.isEmpty() || chosen.size != selectedIds.size || chosen.any { !it.valid })
            return null
        if (chosen.size > availableSlots(draft, editor)) return null
        val existing =
            effectiveRecipients(draft, editor).map(prepare::recipientIdentity).toMutableSet()
        for (row in chosen) {
            if (row.input.service != draft.service || prepare.validateRecipient(row.input) != null)
                return null
            if (prepare.recipientIdentity(row.input) in existing) return null
            existing += prepare.recipientIdentity(row.input)
        }
        return draft.copy(recipients = draft.recipients + chosen.map { it.input })
    }

    /** Reserves room for unfinished nonempty input, preserving even invalid edits across import. */
    fun availableSlots(draft: BulkDraft, editor: BulkRecipientInput?): Int =
        (BulkLimits.MAX_RECIPIENTS - effectiveRecipients(draft, editor).size).coerceAtLeast(0)

    /**
     * Includes the current edit in duplicate/capacity checks without committing or validating it.
     */
    private fun effectiveRecipients(
        draft: BulkDraft,
        editor: BulkRecipientInput?,
    ): List<BulkRecipientInput> {
        val rows = draft.recipients.filterNot { it.id == editor?.id }
        val active = editor?.takeIf {
            it.name.isNotBlank() ||
                it.recipientReference.isNotBlank() ||
                draft.recipients.any { row -> row.id == it.id }
        }
        return rows + listOfNotNull(active)
    }

    /**
     * Validates raw cells before constructing terms, without guessing numeric account identifiers.
     */
    private fun validateRow(
        source: ImportTableRow,
        columns: List<String>,
        draft: BulkDraft,
        existing: Set<Triple<String, BulkService, String>>,
    ): RecipientImportRow {
        val fields =
            columns
                .mapIndexed { index, name ->
                    name to (source.cells.getOrNull(index) ?: ImportCell(""))
                }
                .toMap()
        val service = draft.service
        val provider =
            canonicalChoice(
                fields["provider"]?.text.orEmpty(),
                BulkServiceCatalog.providers(service),
            )
        val product =
            canonicalChoice(fields["product"]?.text.orEmpty(), BulkServiceCatalog.products(service))
        val amount = amountText(fields["amount"])
        val quote = BulkServiceCatalog.quote(service, product)
        val input =
            BulkRecipientInput(
                newId(),
                fields["name"]?.text.orEmpty().trim(),
                fields["recipient"]?.text.orEmpty().trim(),
                provider,
                service,
                quote ?: amount,
                product,
            )
        val declaredService = fields["service"]?.text.orEmpty().trim()
        val issue =
            when {
                source.cells.drop(columns.size).any {
                    it.text.isNotBlank() || it.kind == ImportCellKind.Unsupported
                } -> ImportRowIssue.ExtraCells
                fields
                    .filterKeys {
                        it in setOf("name", "recipient", "provider", "amount", "product", "service")
                    }
                    .values
                    .any { it.kind == ImportCellKind.Unsupported } -> ImportRowIssue.UnsupportedCell
                fields["recipient"]?.kind == ImportCellKind.Number ->
                    ImportRowIssue.NumericReference
                declaredService.isNotBlank() &&
                    !declaredService.equals(service.name, ignoreCase = true) ->
                    ImportRowIssue.ServiceMismatch
                input.name.length > 40 -> ImportRowIssue.NameTooLong
                quote != null &&
                    amount.isNotBlank() &&
                    AmountPolicy.parse(amount) != AmountPolicy.parse(quote) ->
                    ImportRowIssue.PriceMismatch
                prepare.recipientIdentity(input) in existing -> ImportRowIssue.Duplicate
                else -> null
            }
        val paymentIssue = prepare.validateRecipient(input)
        return RecipientImportRow(
            source.number,
            input,
            issue,
            paymentIssue,
            AmountPolicy.parse(input.amount),
        )
    }

    /** Accepts Excel numeric exponents as exact decimals while keeping text amounts strict. */
    private fun amountText(cell: ImportCell?): String {
        val raw = cell?.text.orEmpty().trim()
        if (cell?.kind != ImportCellKind.Number) return raw
        val decimal = raw.toBigDecimalOrNull() ?: return raw
        if (decimal.scale() !in -10..10 || decimal.precision() > 16) return raw
        return decimal.stripTrailingZeros().toPlainString()
    }

    /** Recognizes concise common spreadsheet headings without positional column guessing. */
    private fun headerName(value: String): String =
        when (val key = value.trim().lowercase().replace(Regex("[ _-]"), "")) {
            "phone",
            "phonenumber",
            "recipient",
            "recipientreference",
            "meter",
            "meternumber",
            "smartcard",
            "smartcardnumber" -> "recipient"
            "network",
            "provider" -> "provider"
            "product",
            "plan",
            "package",
            "accounttype",
            "dataplan" -> "product"
            "amount",
            "amountngn" -> "amount"
            "name",
            "recipientname" -> "name"
            else -> key
        }

    /** Normalizes known labels only; unsupported values remain visible as invalid input. */
    private fun canonicalChoice(value: String, choices: List<String>): String =
        choices.firstOrNull { it.equals(value.trim(), ignoreCase = true) } ?: value.trim()

    /** Produces a document-level failure without hiding any parsing or row failure. */
    private fun failure(issue: ImportFileIssue): RecipientImportResult =
        RecipientImportResult.Failed(ImportDocumentResult.Failure(issue))
}
