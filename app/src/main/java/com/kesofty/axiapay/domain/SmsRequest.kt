package com.kesofty.axiapay.domain

import java.time.Instant
import java.time.LocalDate

/** Transport evidence is independent of the payment outcome in the session ledger. */
enum class SmsTransport {
    Draft,
    SendFailed,
    Sent,
}

data class SmsRequest(
    val payment: PaymentRequest,
    val sim: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val transport: SmsTransport = SmsTransport.Draft,
    val attempts: Int = 0,
) {
    val id
        get() = payment.id
}

/** Pure demo transitions. These do not implement a gateway, SMS encoding or authorization. */
object DemoSmsPolicy {
    fun save(session: DemoSession, request: SmsRequest): Outcome<SessionChange<SmsRequest>> {
        val existing = session.smsOutbox.find { it.id == request.id }
        if (existing != null) return replay(session, existing, request)
        if (session.payments.any { it.id == request.id })
            return Outcome.Failure(DemoError.RequestConflict)
        return Outcome.Success(
            SessionChange(session.copy(smsOutbox = listOf(request) + session.smsOutbox), request),
        )
    }

    private fun replay(
        session: DemoSession,
        existing: SmsRequest,
        request: SmsRequest,
    ): Outcome<SessionChange<SmsRequest>> {
        if (existing.payment != request.payment || existing.sim != request.sim)
            return Outcome.Failure(DemoError.RequestConflict)
        return Outcome.Success(SessionChange(session, existing))
    }

    fun send(
        session: DemoSession,
        id: String,
        now: Instant,
        fail: Boolean,
    ): Outcome<SessionChange<SmsRequest>> {
        val request =
            session.smsOutbox.find { it.id == id } ?: return Outcome.Failure(DemoError.NotFound)
        // A repeated tap or restoration cannot start another attempt for a sent request.
        if (request.transport == SmsTransport.Sent)
            return Outcome.Success(SessionChange(session, request))
        if (!now.isBefore(request.expiresAt)) return Outcome.Failure(DemoError.RequestExpired)
        if (request.attempts >= 3) return Outcome.Failure(DemoError.RetryLimit)
        val transport =
            when (fail) {
                true -> SmsTransport.SendFailed
                false -> SmsTransport.Sent
            }
        val updated = request.copy(transport = transport, attempts = request.attempts + 1)
        return Outcome.Success(
            SessionChange(
                session.copy(
                    smsOutbox = session.smsOutbox.map { if (it.id == id) updated else it },
                ),
                updated,
            ),
        )
    }

    /**
     * An explicit simulated backend response resolves the SAME logical request, even after expiry.
     * Expiry only prevents a new send; it must not hide the outcome of an earlier submission.
     */
    fun confirm(
        session: DemoSession,
        id: String,
        today: LocalDate,
    ): Outcome<SessionChange<Payment>> {
        val request =
            session.smsOutbox.find { it.id == id } ?: return Outcome.Failure(DemoError.NotFound)
        if (request.transport != SmsTransport.Sent) return Outcome.Failure(DemoError.SmsNotSent)
        return DemoPaymentPolicy.record(session, request.payment, today)
    }
}
