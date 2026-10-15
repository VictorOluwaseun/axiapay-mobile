package com.kesofty.axiapay.data.imports

import com.kesofty.axiapay.domain.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/**
 * Reads a bounded first XLSX worksheet with Android's SAX API; no OOXML desktop runtime is needed.
 */
internal class OpenXmlExcelReader {
    /**
     * Resolves the first workbook tab through its relationship, accepting shared or inline text.
     */
    fun read(fileName: String, bytes: ByteArray): ImportDocument {
        rejectOlePackage(bytes)
        val entries = unzip(bytes)
        val workbook = elements(required(entries, "xl/workbook.xml"), "sheet")
        val first = workbook.firstOrNull() ?: throw ImportReadException(ImportFileIssue.Empty)
        val relationship =
            elements(required(entries, "xl/_rels/workbook.xml.rels"), "Relationship").singleOrNull {
                it["Id"] == first["id"]
            } ?: throw ImportReadException(ImportFileIssue.InvalidFile)
        if (
            relationship["TargetMode"] == "External" ||
                relationship["Type"]?.endsWith("/worksheet") != true
        )
            throw ImportReadException(ImportFileIssue.InvalidFile)
        val target = relationship["Target"].orEmpty()
        val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
        if (path.split('/').any { it == ".." || it == "." })
            throw ImportReadException(ImportFileIssue.InvalidFile)
        val strings = entries["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        return ImportDocument(fileName, first["name"], worksheet(required(entries, path), strings))
    }

    /** Encrypted OOXML is an OLE container; expose an actionable error instead of a ZIP failure. */
    private fun rejectOlePackage(bytes: ByteArray) {
        if (bytes.take(4) != listOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte()))
            return
        org.apache.poi.poifs.filesystem.POIFSFileSystem(bytes.inputStream()).use { file ->
            val issue =
                if (file.root.hasEntry("EncryptedPackage")) ImportFileIssue.Encrypted
                else ImportFileIssue.InvalidFile
            throw ImportReadException(issue)
        }
    }

    /** Rejects ambiguous archives and limits both compressed input and total inflated bytes. */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var expandedBytes = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entries.size >= 128 || entries.containsKey(entry.name))
                    throw ImportReadException(ImportFileIssue.InvalidFile)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expandedBytes += count
                    if (expandedBytes > 8 * 1024 * 1024)
                        throw ImportReadException(ImportFileIssue.TooLarge)
                    output.write(buffer, 0, count)
                }
                entries[entry.name] = output.toByteArray()
            }
        }
        return entries
    }

    /** Missing package components are a format failure, never an empty successful import. */
    private fun required(entries: Map<String, ByteArray>, path: String): ByteArray =
        entries[path] ?: throw ImportReadException(ImportFileIssue.InvalidFile)

    /** Collects only bounded relationship or sheet attributes with namespace-independent keys. */
    private fun elements(bytes: ByteArray, tag: String): List<Map<String, String>> {
        val values = mutableListOf<Map<String, String>>()
        xml(
            bytes,
            object : SafeXmlHandler() {
                /** Copies attributes before SAX reuses its buffers. */
                override fun startElement(
                    uri: String?,
                    localName: String,
                    qName: String?,
                    attributes: Attributes,
                ) {
                    if (localName != tag) return
                    if (values.size >= 128) throw ImportReadException(ImportFileIssue.TooLarge)
                    values +=
                        (0 until attributes.length).associate {
                            attributes.getLocalName(it) to attributes.getValue(it)
                        }
                }
            },
        )
        return values.toList()
    }

    /** Joins rich-text runs while excluding phonetic annotations from shared string values. */
    private fun sharedStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val text = StringBuilder()
        var inText = false
        var phonetic = false
        xml(
            bytes,
            object : SafeXmlHandler() {
                /** Tracks actual text runs, not formatting or pronunciation metadata. */
                override fun startElement(
                    uri: String?,
                    localName: String,
                    qName: String?,
                    attributes: Attributes,
                ) {
                    when (localName) {
                        "si" -> text.clear()
                        "rPh" -> phonetic = true
                        "t" -> inText = !phonetic
                    }
                }

                /** Bounds retained shared text before allocating a completed string. */
                override fun characters(chars: CharArray, start: Int, length: Int) {
                    if (!inText) return
                    if (text.length + length > 256)
                        throw ImportReadException(ImportFileIssue.TooLarge)
                    text.append(chars, start, length)
                }

                /** Publishes complete shared values only. */
                override fun endElement(uri: String?, localName: String, qName: String?) {
                    when (localName) {
                        "t" -> inText = false
                        "rPh" -> phonetic = false
                        "si" -> {
                            if (strings.size >= 5000)
                                throw ImportReadException(ImportFileIssue.TooLarge)
                            strings += text.toString()
                        }
                    }
                }
            },
        )
        return strings.toList()
    }

    /**
     * Decodes raw values and sparse coordinates; formula caches and numeric identifiers stay typed.
     */
    private fun worksheet(bytes: ByteArray, strings: List<String>): List<ImportTableRow> {
        val table = ImportTableBuilder()
        var row = 0
        var column = -1
        var type = ""
        var formula = false
        var capture = false
        var inCell = false
        var phonetic = false
        val text = StringBuilder()
        xml(
            bytes,
            object : SafeXmlHandler() {
                /** Reads row/cell coordinates without allocating from untrusted dimensions. */
                override fun startElement(
                    uri: String?,
                    localName: String,
                    qName: String?,
                    attributes: Attributes,
                ) {
                    when (localName) {
                        "row" -> {
                            row = attributes.getValue("r")?.toIntOrNull() ?: (row + 1)
                            column = -1
                        }
                        "c" -> {
                            val reference = attributes.getValue("r")
                            column = reference?.let(::columnIndex) ?: (column + 1)
                            if (
                                reference != null &&
                                    reference.dropWhile(Char::isLetter).toIntOrNull() != row
                            )
                                throw ImportReadException(ImportFileIssue.InvalidFile)
                            type = attributes.getValue("t").orEmpty()
                            formula = false
                            text.clear()
                            inCell = true
                        }
                        "rPh" -> phonetic = true
                        "f" -> formula = true
                        "v",
                        "t" -> capture = inCell && !phonetic
                    }
                }

                /** Bounds text even for invalid or formula-bearing cells. */
                override fun characters(chars: CharArray, start: Int, length: Int) {
                    if (!capture) return
                    if (text.length + length > 256)
                        throw ImportReadException(ImportFileIssue.TooLarge)
                    text.append(chars, start, length)
                }

                /** Classifies values before passing them to the application validation boundary. */
                override fun endElement(uri: String?, localName: String, qName: String?) {
                    when (localName) {
                        "v",
                        "t" -> capture = false
                        "rPh" -> phonetic = false
                        "c" -> {
                            table.put(row, column, cell(type, text.toString(), formula, strings))
                            inCell = false
                        }
                    }
                }
            },
        )
        return table.build()
    }

    /** Supports Excel's text encodings; other kinds cannot silently become payment input. */
    private fun cell(
        type: String,
        value: String,
        formula: Boolean,
        strings: List<String>,
    ): ImportCell =
        when {
            formula -> ImportCell("", ImportCellKind.Unsupported)
            type == "s" ->
                ImportCell(
                    strings.getOrNull(value.toIntOrNull() ?: -1)
                        ?: throw ImportReadException(ImportFileIssue.InvalidFile),
                )
            type in listOf("inlineStr", "str") -> ImportCell(value)
            type in listOf("", "n") -> ImportCell(value, ImportCellKind.Number)
            else -> ImportCell(value, ImportCellKind.Unsupported)
        }

    /** Limits column references before arithmetic, preventing overflow and giant sparse tables. */
    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile(Char::isLetter)
        if (letters.length != 1 || letters[0] !in 'A'..'P')
            throw ImportReadException(ImportFileIssue.TooLarge)
        return letters[0] - 'A'
    }

    /** Disables external resolution and rejects DTDs on both Android Expat and desktop test SAX. */
    private fun xml(bytes: ByteArray, handler: SafeXmlHandler) {
        val reader =
            SAXParserFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newSAXParser()
                .xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(bytes.inputStream()))
    }
}

/** Rejects XML constructs that could resolve external content or expand entity declarations. */
private open class SafeXmlHandler : DefaultHandler2() {
    /** Spreadsheet input never needs a document type declaration. */
    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw SAXException("DTD is unsupported")
    }

    /** Prevents access to local paths and remote URLs even if a parser requests resolution. */
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        throw SAXException("External entities are unsupported")
    }
}
