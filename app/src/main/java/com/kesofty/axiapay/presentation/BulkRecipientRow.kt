package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.application.BulkRecipientInput

/** Prepared compact row lets a lazy item render without searching the complete draft again. */
data class BulkRecipientRow(
    val input: BulkRecipientInput,
    val ordinal: Int,
    val amountMinorUnits: Long?,
)

/** Matches human labels and exact identifiers without altering reviewed payment terms. */
internal fun matchesRecipient(
    query: String,
    name: String,
    reference: String,
    provider: String,
): Boolean {
    val normalized = query.trim()
    return normalized.isBlank() ||
        listOf(name, reference, provider).any { it.contains(normalized, ignoreCase = true) }
}
