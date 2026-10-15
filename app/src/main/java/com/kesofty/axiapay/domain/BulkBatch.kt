package com.kesofty.axiapay.domain

import java.time.Instant

/** Services supported by the first bulk preview; real eligibility remains server-owned. */
enum class BulkService {
    Airtime,
    Data,
    Electricity,
    TV,
}

/** Recipient progress is distinct from the aggregate batch result. */
enum class BulkRecipientStatus {
    Queued,
    Processing,
    Successful,
    Failed,
}

/** Summarizes a batch without hiding individual failures or unfinished work. */
enum class BulkBatchStatus {
    Queued,
    Processing,
    Completed,
    PartiallyCompleted,
    Failed,
}

/** Immutable reviewed terms; amounts are NGN kobo and IDs survive retries. */
data class BulkRecipientDetails(
    val id: String,
    val name: String,
    val recipientReference: String,
    val provider: String,
    val service: BulkService,
    val amountMinorUnits: Long,
    val product: String = "",
)

/** An attempt retains its reviewed terms; only known failures become retryable. */
data class BulkRecipient(
    val details: BulkRecipientDetails,
    val status: BulkRecipientStatus = BulkRecipientStatus.Queued,
    val attempts: Int = 1,
)

/** A saved demo batch includes reserved funds in queued/processing rows, never a real ledger. */
data class BulkBatch(
    val id: String,
    val title: String,
    val recipients: List<BulkRecipient>,
    val createdAt: Instant,
    val simulateFailure: Boolean,
) {
    val totalMinorUnits: Long = recipients.sumOf { it.details.amountMinorUnits }

    val successfulCount: Int = recipients.count { it.status == BulkRecipientStatus.Successful }

    val failedCount: Int = recipients.count { it.status == BulkRecipientStatus.Failed }

    val hasPending: Boolean = recipients.any { it.status in pendingStatuses }

    val reservedMinorUnits: Long =
        recipients.filter { it.status in pendingStatuses }.sumOf { it.details.amountMinorUnits }

    val paidMinorUnits: Long =
        recipients
            .filter { it.status == BulkRecipientStatus.Successful }
            .sumOf { it.details.amountMinorUnits }

    val failedMinorUnits: Long =
        recipients
            .filter { it.status == BulkRecipientStatus.Failed }
            .sumOf { it.details.amountMinorUnits }

    val status: BulkBatchStatus =
        when {
            recipients.all { it.status == BulkRecipientStatus.Queued } -> BulkBatchStatus.Queued
            hasPending -> BulkBatchStatus.Processing
            successfulCount == recipients.size -> BulkBatchStatus.Completed
            successfulCount > 0 -> BulkBatchStatus.PartiallyCompleted
            else -> BulkBatchStatus.Failed
        }

    private companion object {
        val pendingStatuses = setOf(BulkRecipientStatus.Queued, BulkRecipientStatus.Processing)
    }
}
