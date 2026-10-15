package com.kesofty.axiapay

import com.kesofty.axiapay.data.imports.*
import com.kesofty.axiapay.domain.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Exercises real file formats and malformed boundaries without involving wallet state. */
class RecipientDocumentParserTest {
    private val parser = RecipientDocumentParser()

    /** All decoders accept the full boundary and reject a 1,001st recipient without truncation. */
    @Test
    fun supportsThousandRowsAcrossCsvXlsxAndLegacyXls() {
        for (count in listOf(1000, 1001)) {
            val rows =
                listOf(listOf("name", "recipient", "provider", "amount")) +
                    (1..count).map {
                        listOf("Demo $it", "0805${it.toString().padStart(7, '0')}", "MTN", "100")
                    }
            val csv = rows.joinToString("\n") { it.joinToString(",") }.toByteArray()
            val xls =
                ByteArrayOutputStream()
                    .also { output ->
                        org.apache.poi.hssf.usermodel.HSSFWorkbook().use { workbook ->
                            val sheet = workbook.createSheet("Recipients")
                            rows.forEachIndexed { index, values ->
                                val row = sheet.createRow(index)
                                values.forEachIndexed { column, value ->
                                    row.createCell(column).setCellValue(value)
                                }
                            }
                            workbook.write(output)
                        }
                    }
                    .toByteArray()
            val xml =
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                    rows
                        .mapIndexed { index, cells ->
                            "<row r=\"${index + 1}\">" +
                                cells
                                    .mapIndexed { column, value ->
                                        "<c r=\"${('A'.code + column).toChar()}${index + 1}\" t=\"inlineStr\"><is><t>$value</t></is></c>"
                                    }
                                    .joinToString("") +
                                "</row>"
                        }
                        .joinToString("") +
                    "</sheetData></worksheet>"
            val xlsx = rewriteEntry("xl/worksheets/recipients.xml") { xml }
            for ((extension, bytes) in listOf("csv" to csv, "xls" to xls, "xlsx" to xlsx)) {
                if (count == 1001) {
                    assertThrows(
                        "$extension must reject overflow",
                        ImportReadException::class.java,
                    ) {
                        parser.parse("scale.$extension", bytes)
                    }
                    continue
                }
                val document = parser.parse("scale.$extension", bytes)
                assertEquals(extension, 1001, document.rows.size)
                assertEquals("08050001000", document.rows.last().cells[1].text)
            }
        }
    }

    /** Loads synthetic resources also installed in the device test APK. */
    private fun fixture(extension: String): ByteArray =
        javaClass.getResourceAsStream("/imports/recipients.$extension")!!.use { it.readBytes() }

    /** All supported formats retain leading zeros and exact text amounts. */
    @Test
    fun readsAllThreeFormats() {
        for (extension in listOf("csv", "xlsx", "xls")) {
            val document = parser.parse("recipients.$extension", fixture(extension))
            assertEquals("08030000001", document.rows[1].cells[1].text)
            assertEquals(ImportCellKind.Text, document.rows[1].cells[1].kind)
            assertEquals("1500.25", document.rows[1].cells[3].text)
            assertEquals("Ada", document.rows[1].cells[0].text)
        }
    }

    /** Excel formula caches never become an amount; numeric identifiers remain explicitly typed. */
    @Test
    fun distinguishesNumericIdentifiersAndFormulas() {
        for (extension in listOf("xlsx", "xls")) {
            val document = parser.parse("recipients.$extension", fixture(extension))
            assertEquals("Recipients", document.sheetName)
            assertEquals(7, document.rows.size)
            assertEquals(ImportCellKind.Number, document.rows[5].cells[1].kind)
            assertEquals(ImportCellKind.Unsupported, document.rows[6].cells[3].kind)
        }
    }

    /** RFC quoting supports embedded delimiters and newlines in optional names. */
    @Test
    fun csvHandlesBomQuotesAndUtf16() {
        val csv =
            "\uFEFFname,recipient,provider,amount\r\n\"Ada, \"\"A\"\"\nLagos\",08030000001,MTN,1500.25\r\n"
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
            val document = parser.parse("recipients.CSV", csv.toByteArray(charset))
            assertEquals("Ada, \"A\"\nLagos", document.rows[1].cells[0].text)
            assertEquals(2, document.rows[1].number)
        }
    }

    /** Unterminated quoting, invalid encodings and oversized input cannot appear successful. */
    @Test
    fun rejectsMalformedCsvAndBounds() {
        assertThrows(Exception::class.java) {
            parser.parse("bad.csv", "\"unterminated".toByteArray())
        }
        assertThrows(Exception::class.java) {
            parser.parse("bad.csv", byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        }
        assertThrows(ImportReadException::class.java) {
            parser.parse("big.csv", ByteArray(RecipientDocumentParser.MAX_FILE_BYTES + 1))
        }
        assertThrows(ImportReadException::class.java) {
            parser.parse("big.csv", (1..1002).joinToString("\n") { "row" }.toByteArray())
        }
        assertThrows(ImportReadException::class.java) {
            parser.parse("big.csv", "x".repeat(257).toByteArray())
        }
    }

    /** DTDs are rejected before entity expansion or external access on the SAX path. */
    @Test
    fun rejectsXmlEntitiesAndExternalWorksheet() {
        val dtd = "<!DOCTYPE workbook [<!ENTITY bad SYSTEM 'file:///does-not-exist'>]>"
        assertThrows(Exception::class.java) {
            parser.parse("bad.xlsx", rewriteWorkbook { dtd + it })
        }
        assertThrows(Exception::class.java) {
            parser.parse(
                "bad.xlsx",
                rewriteEntry("xl/_rels/workbook.xml.rels") {
                    it.replace("Target=", "TargetMode=\"External\" Target=")
                },
            )
        }
    }

    /** Untrusted dimensions and decompression expansion fail without silently dropping records. */
    @Test
    fun rejectsSparseAndExpandedOversizeWorkbooks() {
        assertThrows(ImportReadException::class.java) {
            parser.parse(
                "bad.xlsx",
                rewriteEntry("xl/worksheets/recipients.xml") { it.replace("A2", "XFD2") },
            )
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("huge"))
            zip.write(ByteArray(8 * 1024 * 1024 + 1))
            zip.closeEntry()
        }
        assertThrows(ImportReadException::class.java) {
            parser.parse("bad.xlsx", output.toByteArray())
        }
    }

    /** Empty and mismatched extensions fail at the reader boundary. */
    @Test
    fun rejectsUnsupportedOrCorruptFiles() {
        assertThrows(ImportReadException::class.java) { parser.parse("photo.png", fixture("csv")) }
        assertThrows(Exception::class.java) { parser.parse("broken.xls", fixture("csv")) }
        assertThrows(Exception::class.java) { parser.parse("broken.xlsx", fixture("csv")) }
    }

    /**
     * An unsupported first chart tab must never silently import recipients from a different tab.
     */
    @Test
    fun rejectsFirstChartTabAndPreservesNestedWorksheetCells() {
        val chart = org.apache.poi.hssf.record.BOFRecord.TYPE_CHART
        val worksheet = org.apache.poi.hssf.record.BOFRecord.TYPE_WORKSHEET
        val eof = org.apache.poi.hssf.record.EOFRecord.instance
        val firstChart = legacy(listOf(bof(chart), eof, bof(worksheet), label(0), eof))
        assertThrows(ImportReadException::class.java) { parser.parse("chart.xls", firstChart) }
        val nestedChart = legacy(listOf(bof(worksheet), label(0), bof(chart), eof, label(1), eof))
        assertEquals(listOf(1, 2), parser.parse("nested.xls", nestedChart).rows.map { it.number })
    }

    /** Password-protected modern Excel uses an OLE envelope and receives a specific failure. */
    @Test
    fun encryptedOoxmlIsNotReportedAsAnEmptyZip() {
        val output = ByteArrayOutputStream()
        org.apache.poi.poifs.filesystem.POIFSFileSystem().use { file ->
            file.root.createDocument("EncryptedPackage", byteArrayOf(1, 2, 3).inputStream())
            file.writeFilesystem(output)
        }
        val error =
            assertThrows(ImportReadException::class.java) {
                parser.parse("encrypted.xlsx", output.toByteArray())
            }
        assertEquals(ImportFileIssue.Encrypted, error.issue)
    }

    /** Writes minimal synthetic BIFF substreams to reproduce chart boundaries precisely. */
    private fun legacy(body: List<org.apache.poi.hssf.record.Record>): ByteArray {
        val strings =
            org.apache.poi.hssf.record.SSTRecord().apply {
                addString(org.apache.poi.hssf.record.common.UnicodeString("recipient"))
            }
        val records =
            listOf(
                bof(org.apache.poi.hssf.record.BOFRecord.TYPE_WORKBOOK),
                org.apache.poi.hssf.record.BoundSheetRecord("First").apply { positionOfBof = 100 },
                org.apache.poi.hssf.record.BoundSheetRecord("Second").apply { positionOfBof = 200 },
                strings,
                org.apache.poi.hssf.record.EOFRecord.instance,
            ) + body
        val workbook =
            ByteArrayOutputStream()
                .apply { records.forEach { write(it.serialize()) } }
                .toByteArray()
        val output = ByteArrayOutputStream()
        org.apache.poi.poifs.filesystem.POIFSFileSystem().use { file ->
            file.root.createDocument("Workbook", workbook.inputStream())
            file.writeFilesystem(output)
        }
        return output.toByteArray()
    }

    /** Creates a BIFF8 substream marker without involving the desktop workbook model. */
    private fun bof(type: Int): org.apache.poi.hssf.record.BOFRecord =
        org.apache.poi.hssf.record.BOFRecord().apply {
            this.type = type
            version = 0x600
        }

    /** References the controlled shared header text at a given zero-based row. */
    private fun label(row: Int): org.apache.poi.hssf.record.LabelSSTRecord =
        org.apache.poi.hssf.record.LabelSSTRecord().apply {
            this.row = row
            column = 0
            sstIndex = 0
        }

    /** Edits only the workbook XML inside a synthetic test archive. */
    private fun rewriteWorkbook(transform: (String) -> String): ByteArray =
        rewriteEntry("xl/workbook.xml", transform)

    /** Repackages controlled ZIP content to exercise actual decoding rather than mocked parsers. */
    private fun rewriteEntry(target: String, transform: (String) -> String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { destination ->
            ZipInputStream(fixture("xlsx").inputStream()).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val bytes = source.readBytes()
                    destination.putNextEntry(ZipEntry(entry.name))
                    destination.write(
                        if (entry.name == target)
                            transform(bytes.toString(Charsets.UTF_8)).toByteArray()
                        else bytes,
                    )
                    destination.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
