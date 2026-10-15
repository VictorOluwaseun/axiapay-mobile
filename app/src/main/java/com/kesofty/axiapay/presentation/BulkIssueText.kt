package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.R
import com.kesofty.axiapay.application.BulkInputIssue

/** Maps application validation to localizable UI copy without letting Views invoke validation. */
val BulkInputIssue.messageResource: Int
    get() =
        when (this) {
            BulkInputIssue.ServiceMismatch -> R.string.bulk_validation_service
            BulkInputIssue.EmptyRecipients -> R.string.bulk_validation_empty
            BulkInputIssue.RecipientLimit -> R.string.bulk_validation_limit
            BulkInputIssue.RecipientReference -> R.string.bulk_validation_reference
            BulkInputIssue.Amount -> R.string.bulk_validation_amount
            BulkInputIssue.Provider -> R.string.bulk_validation_provider
            BulkInputIssue.DataPlan -> R.string.bulk_validation_plan
            BulkInputIssue.DuplicateRecipient -> R.string.bulk_validation_duplicate
        }
