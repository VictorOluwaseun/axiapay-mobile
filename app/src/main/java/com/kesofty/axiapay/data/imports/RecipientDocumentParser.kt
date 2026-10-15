package com.kesofty.axiapay.data.imports

import com.kesofty.axiapay.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.apache.commons.csv.CSVFormat

/**
 * Local, bounded decoding of CSV and Excel documents; no formulas, macros or links are executed.
 */
class RecipientDocumentParser {
    /**
     * Uses an explicit supported extension and verifies workbook structure in the format reader.
     */
    fun parse(fileName: String, bytes: ByteArray): ImportDocument {
        if (bytes.size > MAX_FILE_BYTES) throw ImportReadException(ImportFileIssue.TooLarge)
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "csv" -> ImportDocument(fileName, null, csv(bytes))
            "xls" -> LegacyExcelReader().read(fileName, bytes)
            "xlsx" -> OpenXmlExcelReader().read(fileName, bytes)
            else -> throw ImportReadException(ImportFileIssue.UnsupportedFormat)
        }
    }

    /** Supports quoted commas/newlines and UTF-8 or BOM-marked UTF-16 exports without coercion. */
    private fun csv(bytes: ByteArray): List<ImportTableRow> {
        val charset =
            when {
                bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> Charsets.UTF_16LE
                bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
        val text =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        if ('\u0000' in text) throw ImportReadException(ImportFileIssue.InvalidFile)
        val table = ImportTableBuilder()
        CSVFormat.RFC4180.parse(text.reader()).use { parser ->
            for (record in parser) {
                if (record.recordNumber > BulkLimits.MAX_IMPORT_ROWS)
                    throw ImportReadException(ImportFileIssue.TooLarge)
                record.forEachIndexed { column, value ->
                    table.put(record.recordNumber.toInt(), column, ImportCell(value))
                }
            }
        }
        return table.build()
    }

    companion object {
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }
}
