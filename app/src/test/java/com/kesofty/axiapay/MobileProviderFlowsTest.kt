package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Checks expanded demo networks across shared validation without implying live integration. */
class MobileProviderFlowsTest {
    private val addedProviders = listOf("T2 (9mobile)", "Smile")

    /** New network terms survive receipts, recurring plans and SMS preparation without replay. */
    @Test
    fun networksReachIndividualScheduledAndSmsRequests() = runTest {
        for (provider in addedProviders) {
            for (type in listOf(PaymentType.Airtime, PaymentType.Data)) {
                val fixture = DemoTestFixture()
                val input =
                    fixture
                        .input(type, if (type == PaymentType.Data) "3500" else "1250")
                        .copy(provider = provider)
                val payment = fixture.submit(input, "new-network").success()
                val afterPayment = fixture.session.balance
                assertTrue(payment.detail.startsWith(provider + " •"))
                assertEquals(payment, fixture.submit(input, payment.id).success())
                fixture
                    .submit(
                        input.copy(scheduled = true, recurrence = Recurrence.Monthly),
                        "network-plan",
                    )
                    .success()
                assertEquals(
                    payment.detail,
                    fixture.session.plans.single { it.id == "network-plan" }.detail,
                )
                assertEquals(afterPayment, fixture.session.balance)
                UpdateConnectionState(fixture.repository, fixture.clock)(true)
                val sms =
                    PrepareSmsPayment(fixture.repository, fixture.prepare, fixture.clock)(
                            input,
                            "network-sms",
                            1,
                        )
                        .success()
                assertEquals(payment.detail, sms.payment.detail)
                assertEquals(afterPayment, fixture.session.balance)
                assertEquals(1, fixture.session.payments.count { it.id == payment.id })
            }
        }
    }

    /** Imports and bulk entry use the same provider catalogue and exclude international airtime. */
    @Test
    fun bulkAndImportsRetainNewProvidersAndRejectInternational() = runTest {
        for (service in listOf(BulkService.Airtime, BulkService.Data)) {
            val fixture = DemoTestFixture()
            val amount = if (service == BulkService.Data) "3500" else "1250"
            val product = if (service == BulkService.Data) "8 GB" else ""
            val rows = addedProviders.mapIndexed { index, provider ->
                BulkRecipientInput(
                    "row-$index",
                    recipientReference = "0803000000${index + 1}",
                    provider = provider,
                    service = service,
                    product = product,
                    amount = amount,
                )
            }
            val draft =
                BulkDraft(
                    "new-networks",
                    recipients = rows,
                    service = service,
                    simulateFailure = false,
                )
            var nextImportedRow = 0
            val prepareImport =
                PrepareRecipientImport(
                    RecipientImportRepository { error("No document IO expected") },
                    fixture.prepareBulk,
                ) {
                    "imported-row-${nextImportedRow++}"
                }
            val header = listOf("recipient", "provider", "product", "amount")
            val inputRows =
                rows +
                    rows
                        .first()
                        .copy(
                            id = "international",
                            provider = "International airtime",
                            recipientReference = "08030000003",
                        )
            val document =
                ImportDocument(
                    "networks.csv",
                    null,
                    listOf(ImportTableRow(1, header.map(::ImportCell))) +
                        inputRows.mapIndexed { index, row ->
                            ImportTableRow(
                                index + 2,
                                listOf(
                                        row.recipientReference,
                                        row.provider,
                                        row.product,
                                        row.amount,
                                    )
                                    .map(::ImportCell),
                            )
                        },
                )
            val preview =
                (prepareImport.preview(
                        document,
                        draft.copy(recipients = emptyList()),
                        editor = null,
                    ) as RecipientImportResult.Ready)
                    .preview
            assertEquals(2, preview.rows.count { it.valid })
            assertEquals(BulkInputIssue.Provider, preview.rows.last().paymentIssue)
            val before = fixture.session.balance
            val batch = fixture.queueBulk(draft).success()
            assertEquals(addedProviders, batch.recipients.map { it.details.provider })
            assertEquals(before - batch.totalMinorUnits, fixture.session.balance)
            val committed = fixture.session
            assertTrue(
                fixture.queueBulk(
                    draft.copy(id = "unavailable", recipients = listOf(inputRows.last())),
                ) is Outcome.Failure,
            )
            assertEquals(committed, fixture.session)
        }
    }

    /**
     * Disabled-route presentation never makes that provider valid, even through a direct intent.
     */
    @Test
    fun internationalPlaceholderCannotBecomeAPayment() = runTest {
        val fixture = DemoTestFixture()
        val presenter = PaymentFormPresenter(fixture.prepare, fixture.clock)
        val airtime = presenter.create("Airtime", "unavailable-airtime")
        assertTrue(airtime.showsInternationalAirtime)
        assertFalse(presenter.create("Data", "data").showsInternationalAirtime)
        val original = fixture.session
        val input = fixture.input().copy(provider = "International airtime")
        assertEquals(
            Outcome.Failure(DemoError.InvalidProvider),
            fixture.submit(input, airtime.requestId),
        )
        assertEquals(original, fixture.session)
    }
}
