package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.imports.RecipientDocumentParser
import com.kesofty.axiapay.domain.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Keeps all shipped samples aligned with the real import validator and demo service catalog. */
class BundledRecipientSampleTest {
    /**
     * Every format/service supplies three selectable rows with text identifiers and exact prices.
     */
    @Test
    fun everyBundledSampleImportsWithoutCorrections() {
        val fixture = DemoTestFixture()
        val prepare =
            PrepareRecipientImport(
                RecipientImportRepository { error("No IO") },
                fixture.prepareBulk,
            ) {
                UUID.randomUUID().toString()
            }
        for (service in BulkService.entries) {
            val documents =
                RecipientSampleFormat.entries.map { format ->
                    val sample = RecipientSample(service, format)
                    val bytes =
                        File("src/demo/assets/import-samples/${sample.fileName}").readBytes()
                    val document = RecipientDocumentParser().parse(sample.fileName, bytes)
                    val preview =
                        (prepare.preview(document, BulkDraft("sample", service = service), null)
                                as RecipientImportResult.Ready)
                            .preview
                    assertEquals(3, preview.rows.size)
                    assertTrue(preview.rows.all { it.valid })
                    assertTrue(preview.rows.all { it.input.recipientReference.startsWith("0") })
                    assertTrue(
                        document.rows.drop(1).all { it.cells[1].kind == ImportCellKind.Text },
                    )
                    preview.rows.map {
                        listOf(
                            it.input.name,
                            it.input.recipientReference,
                            it.input.provider,
                            it.input.product,
                            it.amountMinorUnits.toString(),
                        )
                    }
                }
            assertEquals(documents.first(), documents.last())
        }
    }
}
