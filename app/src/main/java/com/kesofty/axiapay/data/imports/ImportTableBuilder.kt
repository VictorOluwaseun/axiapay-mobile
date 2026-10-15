package com.kesofty.axiapay.data.imports

import com.kesofty.axiapay.domain.*

/** Signals bounded or unsupported input; the repository maps it to an explicit read failure. */
internal class ImportReadException(val issue: ImportFileIssue) :
    IllegalArgumentException(issue.name)

/** Shared limits apply before retaining cells, including sparse Excel coordinates. */
internal class ImportTableBuilder {
    private val rows = sortedMapOf<Int, MutableMap<Int, ImportCell>>()

    /** Keeps nonempty cells without allocating arrays from untrusted row or column indices. */
    fun put(row: Int, column: Int, cell: ImportCell) {
        if (cell.text.isBlank() && cell.kind != ImportCellKind.Unsupported) return
        if (row !in 1..BulkLimits.MAX_IMPORT_ROWS || column !in 0..15 || cell.text.length > 256)
            throw ImportReadException(ImportFileIssue.TooLarge)
        val cells = rows.getOrPut(row) { sortedMapOf() }
        if (cells.put(column, cell) != null) throw ImportReadException(ImportFileIssue.InvalidFile)
    }

    /** Returns detached row collections, preserving source coordinates and blank interior cells. */
    fun build(): List<ImportTableRow> = rows.map { (number, cells) ->
        ImportTableRow(
            number,
            List((cells.keys.maxOrNull() ?: -1) + 1) { cells[it] ?: ImportCell("") },
        )
    }
}
