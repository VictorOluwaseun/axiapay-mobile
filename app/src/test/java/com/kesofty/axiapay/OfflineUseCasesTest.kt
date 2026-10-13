package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class OfflineUseCasesTest {
    @Test
    fun `review and SMS transmission never debit or imply a payment outcome`() {
        val f = DemoTestFixture()
        UpdateConnectionState(f.repository, f.clock)(true).success()
        val original = f.session
        val request =
            PrepareSmsPayment(f.repository, f.prepare, f.clock)(f.input(), "sms-1", 2).success()
        assertEquals(SmsTransport.Draft, request.transport)
        assertEquals(Duration.ofMinutes(15), Duration.between(request.createdAt, request.expiresAt))
        SendDemoSms(f.repository, f.clock)(request.id, false).success()
        assertEquals(original.balance, f.session.balance)
        assertEquals(original.payments, f.session.payments)
        assertEquals(SmsTransport.Sent, f.session.smsOutbox.single().transport)
        UpdateConnectionState(f.repository, f.clock)(false).success()
        assertEquals(original.balance, f.session.balance)
        assertEquals(original.plans, f.session.plans)
        assertEquals(original.payments, f.session.payments)
    }

    @Test
    fun `failed send retries same immutable request and repeated send is idempotent`() {
        val f = DemoTestFixture()
        val prepare = PrepareSmsPayment(f.repository, f.prepare, f.clock)
        val original = prepare(f.input(), "retry", 1).success()
        val send = SendDemoSms(f.repository, f.clock)
        assertEquals(SmsTransport.SendFailed, send("retry", true).success().transport)
        val sent = send("retry", false).success()
        assertEquals(original.payment, sent.payment)
        assertEquals(original.expiresAt, sent.expiresAt)
        assertEquals(2, sent.attempts)
        assertEquals(sent, send("retry", false).success())
        assertEquals(1, f.session.smsOutbox.size)
        assertEquals(
            Outcome.Failure(DemoError.RequestConflict),
            prepare(f.input(amount = "2000"), "retry", 1),
        )
        assertEquals(Outcome.Failure(DemoError.RequestConflict), prepare(f.input(), "retry", 2))
    }

    @Test
    fun `expired unsent requests cannot send but previously sent requests can reconcile`() {
        val f = DemoTestFixture()
        val prepare = PrepareSmsPayment(f.repository, f.prepare, f.clock)
        prepare(f.input(), "expired", 1).success()
        prepare(f.input(), "sent", 1).success()
        SendDemoSms(f.repository, f.clock)("sent", false).success()
        val later = Clock.offset(f.clock, Duration.ofMinutes(16))
        assertEquals(
            Outcome.Failure(DemoError.RequestExpired),
            SendDemoSms(f.repository, later)("expired", false),
        )
        val paid = ReconcileDemoSms(f.repository, later)("sent").success()
        assertEquals(PaymentStatus.Successful, paid.status)
        assertEquals(12_745_000, f.session.balance)
    }

    @Test
    fun `reconciliation and online submissions cannot duplicate the same SMS debit`() {
        val f = DemoTestFixture()
        PrepareSmsPayment(f.repository, f.prepare, f.clock)(f.input(), "concurrent", 1).success()
        SendDemoSms(f.repository, f.clock)("concurrent", false).success()
        val reconcile = ReconcileDemoSms(f.repository, f.clock)
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll(List(20) { Callable { reconcile("concurrent").success() } }).forEach {
                it.get()
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(12_745_000, f.session.balance)
        assertEquals(1, f.session.payments.count { it.id == "concurrent" })
        assertEquals(
            Outcome.Failure(DemoError.SmsAlreadySubmitted),
            f.submit(f.input(), "concurrent"),
        )
    }

    @Test
    fun `insufficient funds remain pending after funding until explicit completion`() {
        val f = DemoTestFixture()
        val input = f.input(amount = "130000")
        PrepareSmsPayment(f.repository, f.prepare, f.clock)(input, "fund-later", 1).success()
        SendDemoSms(f.repository, f.clock)("fund-later", false).success()
        val reconcile = ReconcileDemoSms(f.repository, f.clock)
        assertEquals(PaymentStatus.Waiting, reconcile("fund-later").success().status)
        f.submit(f.input(PaymentType.Funding, "5000"), "fund").success()
        assertEquals(PaymentStatus.Waiting, reconcile("fund-later").success().status)
        assertEquals(13_345_000, f.session.balance)
        f.complete("fund-later").success()
        assertEquals(PaymentStatus.Successful, reconcile("fund-later").success().status)
        assertEquals(345_000, f.session.balance)
    }

    @Test
    fun `offline disallows online-only actions without changing accepted plans`() {
        val f = DemoTestFixture()
        val before = f.session
        UpdateConnectionState(f.repository, f.clock)(true).success()
        assertEquals(Outcome.Failure(DemoError.OnlineRequired), f.submit(f.input(), "online"))
        assertEquals(Outcome.Failure(DemoError.OnlineRequired), f.batch())
        assertEquals(
            Outcome.Failure(DemoError.OnlineRequired),
            SetPlanEnabled(f.repository)("plan-data", false),
        )
        assertEquals(Outcome.Failure(DemoError.OnlineRequired), f.complete("anything"))
        assertEquals(before.plans, f.session.plans)
        assertEquals(before.balance, f.session.balance)
        val prepare = PrepareSmsPayment(f.repository, f.prepare, f.clock)
        assertEquals(
            Outcome.Failure(DemoError.SmsUnsupported),
            prepare(f.input(PaymentType.Funding), "fund", 1),
        )
        assertEquals(
            Outcome.Failure(DemoError.SmsUnsupported),
            prepare(f.input().copy(scheduled = true), "schedule", 1),
        )
        assertTrue(prepare(f.input(PaymentType.Data, "3500"), "data", 1) is Outcome.Success)
        assertEquals(
            Outcome.Failure(DemoError.InvalidRecipient),
            prepare(f.input().copy(recipient = "123"), "invalid", 1),
        )
    }

    @Test
    fun `retry limit unsent confirmation and storage errors retain honest state`() {
        val f = DemoTestFixture()
        PrepareSmsPayment(f.repository, f.prepare, f.clock)(f.input(), "failed", 1).success()
        assertEquals(
            Outcome.Failure(DemoError.SmsNotSent),
            ReconcileDemoSms(f.repository, f.clock)("failed"),
        )
        val send = SendDemoSms(f.repository, f.clock)
        f.store.failSave = true
        assertTrue(send("failed", false) is Outcome.Failure)
        assertEquals(SmsTransport.Draft, f.session.smsOutbox.single().transport)
        f.store.failSave = false
        repeat(3) { send("failed", true).success() }
        assertEquals(Outcome.Failure(DemoError.RetryLimit), send("failed", false))
        assertEquals(12_845_000, f.session.balance)
    }
}
