package com.kesofty.axiapay.domain

/** Bounded local batch capacity shared by validation, file adapters and presentation. */
object BulkLimits {
    const val MAX_RECIPIENTS = 1_000
    const val MAX_IMPORT_ROWS = MAX_RECIPIENTS + 1 // Includes the header, never silently truncates.
}
