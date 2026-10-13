package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class DemoPaymentUseCasesTest {
    @Test
    fun `amount parsing preserves exact minor units and rejects malformed values`() {
        assertEquals(10001L, AmountPolicy.parse("100.01"))
        listOf("", "0", "-5", "1.001", "1e4", "1000001", "NaN", "1,000").forEach {
            assertNull(AmountPolicy.parse(it))
        }
    }

    @Test
    fun `successful payment and repeated logical request debit only once`() {
        val f = DemoTestFixture()
        val initial = f.session.balance
        val payment = f.submit(f.input(), "payment-1").success()
        assertEquals(PaymentStatus.Successful, payment.status)
        assertEquals(payment, f.submit(f.input(), "payment-1").success())
        f.complete(payment.id)
        assertEquals(initial - 100_000, f.session.balance)
        assertEquals(1, f.session.payments.count { it.id == payment.id })
    }

    @Test
    fun `reusing a request ID with different details is an explicit conflict`() {
        val f = DemoTestFixture()
        f.submit(f.input(), "payment-1")
        val before = f.session
        assertEquals(
            Outcome.Failure(DemoError.RequestConflict),
            f.submit(f.input(amount = "2000"), "payment-1"),
        )
        assertEquals(before, f.session)
    }

    @Test
    fun `waiting request requires funding and explicit completion exactly once`() {
        val f = DemoTestFixture()
        val initial = f.session.balance
        val payment = f.submit(f.input(amount = "129450"), "waiting").success()
        assertEquals(PaymentStatus.Waiting, payment.status)
        assertEquals(initial, f.session.balance)
        assertEquals(Outcome.Failure(DemoError.InsufficientFunds), f.complete(payment.id))
        f.submit(f.input(PaymentType.Funding, "2000"), "funding")
        assertEquals(initial + 200_000, f.session.balance)
        assertEquals(PaymentStatus.Waiting, f.session.payments.first { it.id == payment.id }.status)
        f.complete(payment.id)
        f.complete(payment.id)
        assertEquals(100_000, f.session.balance)
    }

    @Test
    fun `every recurrence schedules without spending and preserves cadence when paused`() {
        for (frequency in Recurrence.entries) {
            val f = DemoTestFixture()
            val initial = f.session.balance
            val payment =
                f.submit(f.input().copy(scheduled = true, recurrence = frequency), "schedule")
                    .success()
            assertEquals(PaymentStatus.Scheduled, payment.status)
            assertEquals(initial, f.session.balance)
            assertEquals(frequency, f.session.plans.last().recurrence)
            SetPlanEnabled(f.repository)(payment.id, false)
            SetPlanEnabled(f.repository)(payment.id, false)
            assertFalse(f.session.plans.last().enabled)
            SetPlanEnabled(f.repository)(payment.id, true)
            assertEquals(frequency, f.session.plans.last().recurrence)
            assertEquals(initial, f.session.balance)
        }
    }

    @Test
    fun `invalid recipients providers quotes and dates cannot mutate session`() {
        val f = DemoTestFixture()
        val initial = f.session
        val invalid =
            listOf(
                f.input().copy(recipient = "123"),
                f.input().copy(provider = "Unknown"),
                f.input().copy(amount = "0"),
                f.input().copy(scheduled = true, date = f.today),
                f.input(PaymentType.Data).copy(dataPlan = "8 GB", amount = "1"),
                f.input(PaymentType.Transfer)
                    .copy(
                        destination = TransferDestination.Bank,
                        provider = "GTBank",
                        recipient = "123",
                    ),
            )
        invalid.forEachIndexed { index, input ->
            assertTrue(f.submit(input, "invalid-$index") is Outcome.Failure)
        }
        assertEquals(initial, f.session)
    }

    @Test
    fun `wallet and bank transfers accept their respective recipient formats`() {
        val f = DemoTestFixture()
        assertTrue(
            f.submit(f.input(PaymentType.Transfer).copy(recipient = "@alex"), "wallet")
                is Outcome.Success,
        )
        assertTrue(
            f.submit(
                f.input(PaymentType.Transfer)
                    .copy(
                        destination = TransferDestination.Bank,
                        provider = "GTBank",
                        recipient = "0123456789",
                    ),
                "bank",
            ) is Outcome.Success,
        )
    }

    @Test
    fun `failed snapshot write leaves no debit receipt or plan and same request can recover`() {
        val f = DemoTestFixture()
        val initial = f.session
        f.store.failSave = true
        val result = f.submit(f.input(), "recover") as Outcome.Failure
        assertTrue(result.error is DemoError.Storage)
        assertEquals("Simulated save failure", (result.error as DemoError.Storage).cause.message)
        assertEquals(initial, f.session)
        f.store.failSave = false
        f.submit(f.input(), "recover")
        assertEquals(initial.balance - 100_000, f.session.balance)
    }

    @Test
    fun `batch retries reuse one recipient request and never repeat successful recipients`() {
        val f = DemoTestFixture()
        val before = f.session.balance
        val first = f.batch().success()
        assertEquals(first.id, f.batch().success().id)
        assertEquals(before - 100_000, f.session.balance)
        assertEquals(1, f.session.payments.count { it.kind == PaymentKind.Bulk })
    }

    @Test
    fun `concurrent requests cannot overspend or duplicate a funding command`() {
        val f = DemoTestFixture(MemorySessionStore(DemoSession(balance = 150_000)))
        val pool = Executors.newFixedThreadPool(8)
        try {
            pool
                .invokeAll((1..20).map { n -> Callable { f.submit(f.input(), "concurrent-$n") } })
                .forEach { it.get() }
            assertEquals(50_000, f.session.balance)
            assertEquals(1, f.session.payments.count { it.status == PaymentStatus.Successful })
            assertEquals(19, f.session.payments.count { it.status == PaymentStatus.Waiting })
            pool
                .invokeAll(
                    (1..20).map {
                        Callable { f.submit(f.input(PaymentType.Funding), "same-funding") }
                    },
                )
                .forEach { it.get() }
            assertEquals(150_000, f.session.balance)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `monthly calendar dates retain their anchor through short and leap months`() {
        assertEquals(
            listOf(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)),
            Recurrence.Monthly.upcomingDates(LocalDate.of(2027, 1, 31)),
        )
        assertEquals(
            listOf(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 31)),
            Recurrence.Monthly.upcomingDates(LocalDate.of(2028, 1, 31)),
        )
        val start = LocalDate.of(2026, 12, 30)
        assertEquals(listOf(start), Recurrence.Once.upcomingDates(start))
        assertEquals(
            listOf(start, start.plusDays(1), start.plusDays(2)),
            Recurrence.Daily.upcomingDates(start),
        )
        assertEquals(
            listOf(start, start.plusWeeks(1), start.plusWeeks(2)),
            Recurrence.Weekly.upcomingDates(start),
        )
    }
}
