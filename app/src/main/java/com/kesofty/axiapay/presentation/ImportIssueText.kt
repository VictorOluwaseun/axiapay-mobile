package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.R
import com.kesofty.axiapay.application.ImportRowIssue
import com.kesofty.axiapay.domain.ImportFileIssue

/** Safe localizable file recovery copy never exposes exception messages or document contents. */
val ImportFileIssue.messageResource: Int
    get() =
        when (this) {
            ImportFileIssue.UnsupportedFormat -> R.string.import_unsupported
            ImportFileIssue.Unreadable -> R.string.import_unreadable
            ImportFileIssue.InvalidFile -> R.string.import_invalid
            ImportFileIssue.TooLarge -> R.string.import_too_large
            ImportFileIssue.Encrypted -> R.string.import_encrypted
            ImportFileIssue.Headers -> R.string.import_headers_error
            ImportFileIssue.Empty -> R.string.import_empty
        }

/** Each rejected row gives an actionable correction before any recipient joins a draft. */
val ImportRowIssue.messageResource: Int
    get() =
        when (this) {
            ImportRowIssue.NumericReference -> R.string.import_numeric_reference
            ImportRowIssue.UnsupportedCell -> R.string.import_formula
            ImportRowIssue.Duplicate -> R.string.import_duplicate
            ImportRowIssue.ServiceMismatch -> R.string.import_service_mismatch
            ImportRowIssue.PriceMismatch -> R.string.import_price_mismatch
            ImportRowIssue.NameTooLong -> R.string.import_name_long
            ImportRowIssue.ExtraCells -> R.string.import_extra_cells
        }
