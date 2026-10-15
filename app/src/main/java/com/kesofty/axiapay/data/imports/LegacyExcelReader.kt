package com.kesofty.axiapay.data.imports

import com.kesofty.axiapay.domain.*
import java.math.BigDecimal
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory
import org.apache.poi.hssf.eventusermodel.HSSFRequest
import org.apache.poi.hssf.record.*
import org.apache.poi.poifs.filesystem.POIFSFileSystem

/** Reads BIFF8 .xls cells through POI's event API, without the desktop workbook/rendering APIs. */
internal class LegacyExcelReader {
    /** Uses the first tab's worksheet; cached formula results are deliberately never accepted. */
    fun read(fileName: String, bytes: ByteArray): ImportDocument {
        val table = ImportTableBuilder()
        val sheets = mutableListOf<BoundSheetRecord>()
        var strings: SSTRecord? = null
        var sheetIndex = -1
        var active = false
        var depth = 0
        POIFSFileSystem(bytes.inputStream()).use { file ->
            if (file.root.hasEntry("EncryptedPackage"))
                throw ImportReadException(ImportFileIssue.Encrypted)
            val request = HSSFRequest()
            request.addListenerForAllRecords { record ->
                when (record) {
                    is FilePassRecord -> throw ImportReadException(ImportFileIssue.Encrypted)
                    is BoundSheetRecord -> {
                        if (sheets.size >= 128) throw ImportReadException(ImportFileIssue.TooLarge)
                        sheets += record
                    }
                    is SSTRecord -> {
                        if (record.numUniqueStrings > 5000)
                            throw ImportReadException(ImportFileIssue.TooLarge)
                        strings = record
                    }
                    is BOFRecord -> {
                        val startsSheet = depth == 0 && record.type != BOFRecord.TYPE_WORKBOOK
                        if (startsSheet) sheetIndex += 1
                        val selected = startsSheet && sheetIndex == firstPhysicalSheet(sheets)
                        if (selected && record.type != BOFRecord.TYPE_WORKSHEET)
                            throw ImportReadException(ImportFileIssue.InvalidFile)
                        if (depth == 0) active = selected
                        depth += 1
                    }
                    is EOFRecord -> {
                        if (depth <= 0) throw ImportReadException(ImportFileIssue.InvalidFile)
                        depth -= 1
                        if (depth == 0) active = false
                    }
                    else -> if (active && depth == 1) putRecord(record, strings, table)
                }
            }
            HSSFEventFactory().processWorkbookEvents(request, file)
        }
        return ImportDocument(fileName, sheets.firstOrNull()?.sheetname, table.build())
    }

    /** Resolves workbook tab order rather than assuming the first stored sheet is the first tab. */
    private fun firstPhysicalSheet(sheets: List<BoundSheetRecord>): Int {
        val first = sheets.firstOrNull() ?: return 0
        return sheets.sortedBy { it.positionOfBof }.indexOf(first)
    }

    /** Retains text precisely and marks formulas/errors/booleans as unsupported values. */
    private fun putRecord(
        record: org.apache.poi.hssf.record.Record,
        strings: SSTRecord?,
        table: ImportTableBuilder,
    ) {
        when (record) {
            is LabelSSTRecord ->
                table.put(
                    record.row + 1,
                    record.column.toInt(),
                    ImportCell(requireNotNull(strings).getString(record.sstIndex).string),
                )
            is LabelRecord ->
                table.put(record.row + 1, record.column.toInt(), ImportCell(record.value))
            is NumberRecord ->
                table.put(record.row + 1, record.column.toInt(), number(record.value))
            is RKRecord -> table.put(record.row + 1, record.column.toInt(), number(record.rkNumber))
            is MulRKRecord ->
                (0 until record.numColumns).forEach { index ->
                    table.put(
                        record.row + 1,
                        record.firstColumn + index,
                        number(record.getRKNumberAt(index)),
                    )
                }
            is FormulaRecord ->
                table.put(
                    record.row + 1,
                    record.column.toInt(),
                    ImportCell("", ImportCellKind.Unsupported),
                )
            is BoolErrRecord ->
                table.put(
                    record.row + 1,
                    record.column.toInt(),
                    ImportCell("", ImportCellKind.Unsupported),
                )
            else -> Unit
        }
    }

    /** Decodes Excel numeric storage as decimal text; no money arithmetic uses binary floats. */
    private fun number(value: Double): ImportCell =
        ImportCell(
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(),
            ImportCellKind.Number,
        )
}
