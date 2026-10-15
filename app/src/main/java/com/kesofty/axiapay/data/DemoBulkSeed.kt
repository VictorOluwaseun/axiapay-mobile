package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.*
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Completed historical fixtures are illustrative; adding them never changes the opening balance.
 */
internal object DemoBulkSeed {
    /** Seeds one completed and one partly completed batch with stable retryable recipient IDs. */
    fun history(today: LocalDate): List<BulkBatch> {
        val recipients =
            listOf(
                BulkRecipient(
                    BulkRecipientDetails(
                        "ada",
                        "Ada",
                        "08030000001",
                        "MTN",
                        BulkService.Airtime,
                        150_000,
                    ),
                    BulkRecipientStatus.Successful,
                ),
                BulkRecipient(
                    BulkRecipientDetails(
                        "tunde",
                        "Tunde",
                        "08020000002",
                        "Airtel",
                        BulkService.Data,
                        100_000,
                        "2 GB",
                    ),
                    BulkRecipientStatus.Successful,
                ),
                BulkRecipient(
                    BulkRecipientDetails(
                        "home",
                        "Home meter",
                        "12345678901",
                        "Ikeja Electric",
                        BulkService.Electricity,
                        450_000,
                        "Prepaid",
                    ),
                    BulkRecipientStatus.Successful,
                ),
                BulkRecipient(
                    BulkRecipientDetails(
                        "tv",
                        "Family TV",
                        "1234567890",
                        "DStv",
                        BulkService.TV,
                        500_000,
                        "Basic preview",
                    ),
                    BulkRecipientStatus.Successful,
                ),
            )
        return listOf(
            BulkBatch(
                "sample-family-batch",
                "Family essentials",
                recipients,
                today.minusDays(1).atTime(9, 30).toInstant(ZoneOffset.UTC),
                false,
            ),
            BulkBatch(
                "sample-weekend-batch",
                "Weekend top-ups",
                recipients.take(2).mapIndexed { index, recipient ->
                    recipient.copy(
                        status =
                            if (index == 1) BulkRecipientStatus.Failed
                            else BulkRecipientStatus.Successful,
                    )
                },
                today.minusDays(3).atTime(16, 15).toInstant(ZoneOffset.UTC),
                true,
            ),
        )
    }
}
