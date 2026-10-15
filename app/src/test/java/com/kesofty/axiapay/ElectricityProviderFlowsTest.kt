package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Checks that new demo choices reach scheduled and bulk operations through shared validation. */
class ElectricityProviderFlowsTest {
    /**
     * Added providers keep their identity and exact amounts without bypassing payment use cases.
     */
    @Test
    fun addedProvidersCanBeScheduledAndQueuedForBulk() = runTest {
        val fixture = DemoTestFixture()
        val providers =
            listOf(
                "Kano Electric",
                "Port Harcourt Electric",
                "Jos Electric",
                "Kaduna Electric",
                "Enugu Electric",
                "Ibadan Electric",
                "Benin Electric",
                "Aba Electric",
                "Yola Electric",
            )
        val initialBalance = fixture.session.balance
        providers.forEachIndexed { index, provider ->
            val input =
                fixture
                    .input(PaymentType.Electricity, "4500")
                    .copy(
                        provider = provider,
                        recipient = "01234567890",
                        scheduled = true,
                    )
            val payment = fixture.submit(input, "electricity-plan-$index").success()
            assertEquals(PaymentStatus.Scheduled, payment.status)
            assertTrue(payment.title.contains(provider))
        }
        assertEquals(initialBalance, fixture.session.balance)
        val rows = providers.mapIndexed { index, provider ->
            BulkRecipientInput(
                id = "meter-$index",
                recipientReference = "01234567890",
                provider = provider,
                service = BulkService.Electricity,
                amount = "4500",
                product = "Prepaid",
            )
        }
        val batch =
            fixture
                .queueBulk(
                    BulkDraft("electricity-batch", recipients = rows, simulateFailure = false),
                )
                .success()
        assertEquals(providers, batch.recipients.map { it.details.provider })
        assertEquals(4_050_000L, batch.totalMinorUnits)
        assertEquals(initialBalance - batch.totalMinorUnits, fixture.session.balance)
    }
}
