package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.imports.RecipientDocumentParser
import com.kesofty.axiapay.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Verifies import validation and merging preserve exact terms and existing draft ownership. */
class PrepareRecipientImportTest {
    private val fixture = DemoTestFixture()
    private var counter = 0
    private val prepare =
        PrepareRecipientImport(
            RecipientImportRepository { error("No IO expected") },
            fixture.prepareBulk,
        ) {
            "import-${counter++}"
        }
    private val draft = BulkDraft("draft", service = BulkService.Airtime)

    /**
     * Builds raw rows with explicit headers to exercise application mappings independently of
     * parsers.
     */
    private fun document(vararg rows: List<String>): ImportDocument =
        ImportDocument(
            "list.csv",
            null,
            rows.mapIndexed { index, cells -> ImportTableRow(index + 1, cells.map(::ImportCell)) },
        )

    /** Extracts only a successful validation preview. */
    private fun preview(
        document: ImportDocument,
        draft: BulkDraft = this.draft,
        editor: BulkRecipientInput? = null,
    ): RecipientImportPreview =
        (prepare.preview(document, draft, editor) as RecipientImportResult.Ready).preview

    /**
     * Numeric identifiers and formulas are rejected, while valid Excel/CSV rows remain selectable.
     */
    @Test
    fun formatsProduceEquivalentValidatedTerms() {
        for (extension in listOf("csv", "xls", "xlsx")) {
            val bytes =
                javaClass.getResourceAsStream("/imports/recipients.$extension")!!.use {
                    it.readBytes()
                }
            val preview = preview(RecipientDocumentParser().parse("recipients.$extension", bytes))
            assertEquals(2, preview.rows.count { it.valid })
            assertEquals(150025L, preview.rows[0].amountMinorUnits)
            assertEquals(ImportRowIssue.Duplicate, preview.rows[2].issue)
            assertEquals(BulkInputIssue.RecipientReference, preview.rows[3].paymentIssue)
        }
    }

    /** Existing canonical identities and unfinished input both participate in duplicate checks. */
    @Test
    fun preservesExistingRowsAndEditorAndNormalizesDuplicatePhones() {
        val existing = BulkRecipientInput("manual", recipientReference = "08030000001")
        val editor =
            BulkRecipientInput("editor", recipientReference = "08030000002", amount = "invalid")
        val initial = draft.copy(recipients = listOf(existing))
        val preview =
            preview(
                document(
                    listOf("recipient", "provider", "amount"),
                    listOf("+2348030000001", "mtn", "1000"),
                    listOf("08030000002", "MTN", "1000"),
                    listOf("08030000003", "Airtel", "1250.25"),
                ),
                initial,
                editor,
            )
        assertEquals(998, preview.availableSlots)
        assertEquals(listOf(false, false, true), preview.rows.map { it.valid })
        val merged = prepare.append(preview, setOf(preview.rows[2].input.id), initial, editor)!!
        assertEquals(existing, merged.recipients.first())
        assertEquals("1250.25", merged.recipients.last().amount)
        assertEquals("invalid", editor.amount)
        assertEquals(1, initial.recipients.size)
    }

    /** Invalid earlier occurrences do not prevent importing a later corrected row. */
    @Test
    fun invalidRowsDoNotClaimDuplicateIdentity() {
        val preview =
            preview(
                document(
                    listOf("phone", "network", "amount"),
                    listOf("08030000001", "MTN", "bad"),
                    listOf("08030000001", "MTN", "1500"),
                ),
            )
        assertFalse(preview.rows.first().valid)
        assertTrue(preview.rows.last().valid)
    }

    /** Imports require explicit products and reject fixed-price mismatches across all services. */
    @Test
    fun enforcesServiceProductAndPriceWithoutGuessing() {
        for ((service, provider, product) in
            listOf(
                Triple(BulkService.Data, "MTN", "2 GB"),
                Triple(BulkService.TV, "DStv", "Basic preview"),
                Triple(BulkService.Electricity, "Ikeja Electric", "Prepaid"),
            )) {
            val current = draft.copy(service = service)
            val number = if (service == BulkService.Data) "08030000001" else "01234567890"
            val preview =
                preview(
                    document(
                        listOf("recipient", "provider", "product", "amount", "service"),
                        listOf(number, provider, product, "3500.25", service.name),
                        listOf(number, provider, product, "1000", "Wrong"),
                    ),
                    current,
                )
            assertEquals(ImportRowIssue.ServiceMismatch, preview.rows.last().issue)
            assertEquals(service == BulkService.Electricity, preview.rows.first().valid)
        }
    }

    /** Missing/ambiguous headings and extra CSV cells produce actionable failures. */
    @Test
    fun rejectsAmbiguousHeadersAndUnheadedValues() {
        val missing =
            prepare.preview(
                document(listOf("recipient", "amount"), listOf("08030000001", "1000")),
                draft,
                null,
            )
        assertEquals(
            ImportFileIssue.Headers,
            (missing as RecipientImportResult.Failed).failure.issue,
        )
        val duplicated =
            prepare.preview(
                document(listOf("recipient", "phone", "provider", "amount")),
                draft,
                null,
            )
        assertTrue(duplicated is RecipientImportResult.Failed)
        val extra =
            preview(
                document(
                    listOf("recipient", "provider", "amount"),
                    listOf("08030000001", "MTN", "1", "500"),
                ),
            )
        assertEquals(ImportRowIssue.ExtraCells, extra.rows.single().issue)
    }

    /** Capacity is never handled by silent truncation; stale and repeated merges are rejected. */
    @Test
    fun capacityAndStalePreviewRequireExplicitSelection() {
        val existing =
            (1..999).map {
                BulkRecipientInput(
                    "old-$it",
                    recipientReference = "0804" + it.toString().padStart(7, '0'),
                )
            }
        val current = draft.copy(recipients = existing)
        val preview =
            preview(
                document(
                    listOf("recipient", "provider", "amount"),
                    listOf("08030000001", "MTN", "1000"),
                    listOf("08030000002", "MTN", "2000"),
                ),
                current,
            )
        assertEquals(1, preview.availableSlots)
        assertNull(prepare.append(preview, preview.rows.map { it.input.id }.toSet(), current, null))
        val selected = setOf(preview.rows.first().input.id)
        val merged = prepare.append(preview, selected, current, null)!!
        assertEquals(1000, merged.recipients.size)
        assertNull(prepare.append(preview, selected, merged, null))
        assertNull(prepare.append(preview, setOf("unknown"), current, null))
    }

    /** Numeric amount exponents decode exactly while numeric identifiers are still rejected. */
    @Test
    fun handlesNumericAmountsWithoutFloatingPointArithmetic() {
        val raw =
            document(
                listOf("recipient", "provider", "amount"),
                listOf("08030000001", "MTN", "1.25025E3"),
            )
        val row =
            raw.rows[1].copy(
                cells =
                    raw.rows[1].cells.mapIndexed { index, cell ->
                        if (index == 2) cell.copy(kind = ImportCellKind.Number) else cell
                    },
            )
        assertEquals(
            125025L,
            preview(raw.copy(rows = listOf(raw.rows[0], row))).rows.single().amountMinorUnits,
        )
    }
}
