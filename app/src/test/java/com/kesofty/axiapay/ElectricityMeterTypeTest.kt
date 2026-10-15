package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Protects meter billing terms across demo submission, recurring plans and bulk conversion. */
class ElectricityMeterTypeTest {
    /** Both choices reach receipts and plans; scheduling still cannot debit the wallet. */
    @Test
    fun meterTermsReachReceiptsAndRecurringPlans() = runTest {
        ElectricityMeterType.entries.forEach { meterType ->
            val fixture = DemoTestFixture()
            val input = electricityInput(fixture, meterType)
            val initialBalance = fixture.session.balance
            val payment = fixture.submit(input, "electricity-now").success()
            assertEquals(PaymentStatus.Successful, payment.status)
            assertEquals("Eko Electric • ${meterType.name} • 01234567890", payment.detail)
            val balanceAfterPayment = initialBalance - 450_000L
            assertEquals(balanceAfterPayment, fixture.session.balance)
            val scheduled =
                fixture
                    .submit(
                        input.copy(scheduled = true, recurrence = Recurrence.Monthly),
                        "electricity-plan",
                    )
                    .success()
            assertEquals(PaymentStatus.Scheduled, scheduled.status)
            assertEquals(payment.detail, scheduled.detail)
            val plan = fixture.session.plans.single { it.id == scheduled.id }
            assertEquals(payment.detail, plan.detail)
            assertEquals(Recurrence.Monthly, plan.recurrence)
            assertEquals(balanceAfterPayment, fixture.session.balance)
        }
    }

    /** A logical request cannot be reused with changed billing terms or charge the wallet again. */
    @Test
    fun changedMeterTypeConflictsWithACommittedRequest() = runTest {
        val fixture = DemoTestFixture()
        val input = electricityInput(fixture, ElectricityMeterType.Prepaid)
        val payment = fixture.submit(input, "same-electricity").success()
        val committed = fixture.session
        assertEquals(payment, fixture.submit(input, payment.id).success())
        assertEquals(
            Outcome.Failure(DemoError.RequestConflict),
            fixture.submit(
                input.copy(electricityMeterType = ElectricityMeterType.Postpaid),
                payment.id,
            ),
        )
        assertEquals(committed, fixture.session)
    }

    /** Moving a Postpaid form to bulk retains its terms for the first and next recipients. */
    @Test
    fun postpaidSurvivesIndividualToBulkConversion() = runTest {
        val fixture = DemoTestFixture()
        var nextIdentity = 0
        val presenter = BulkFormPresenter(fixture.prepareBulk) { "meter-row-${nextIdentity++}" }
        val form =
            PaymentFormState(
                "individual-meter",
                "Electricity",
                electricityInput(fixture, ElectricityMeterType.Postpaid),
            )
        val bulk = presenter.fromPayment(BulkFlowState(), form)
        assertEquals("Postpaid", bulk.draft!!.recipients.single().product)
        assertEquals("Postpaid", bulk.editor!!.product)
        assertEquals("", bulk.editor!!.recipientReference)
        val batch = fixture.queueBulk(bulk.draft!!).success()
        assertEquals("Postpaid", batch.recipients.single().details.product)
        assertEquals(450_000L, batch.totalMinorUnits)
    }

    /** Builds valid demo electricity input without exposing real provider eligibility. */
    private fun electricityInput(fixture: DemoTestFixture, meterType: ElectricityMeterType) =
        fixture
            .input(PaymentType.Electricity, "4500")
            .copy(
                provider = "Eko Electric",
                recipient = "01234567890",
                electricityMeterType = meterType,
            )
}
