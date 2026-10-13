package com.kesofty.axiapay.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Simulates the existing UI demo. This is explicitly not an authoritative client ledger. */
object DemoPaymentPolicy {
    fun record(
        session: DemoSession,
        request: PaymentRequest,
        today: LocalDate,
    ): Outcome<SessionChange<Payment>> {
        val existing = session.payments.find { it.id == request.id }
        if (existing != null) return replay(session, existing, request)
        if (!AmountPolicy.isValid(request.amount)) return Outcome.Failure(DemoError.InvalidAmount)
        if (request.date != null && !request.date.isAfter(today))
            return Outcome.Failure(DemoError.InvalidDate)
        if (request.date != null) return schedule(session, request)
        if (request.kind == PaymentKind.Funding) return fund(session, request)
        val status =
            when {
                session.balance >= request.amount -> PaymentStatus.Successful
                else -> PaymentStatus.Waiting
            }
        val debit =
            when (status) {
                PaymentStatus.Successful -> request.amount
                else -> 0L
            }
        val payment = request.payment(-request.amount, status)
        return Outcome.Success(
            SessionChange(
                session.copy(
                    balance = session.balance - debit,
                    payments = listOf(payment) + session.payments,
                ),
                payment,
            ),
        )
    }

    private fun replay(
        session: DemoSession,
        payment: Payment,
        request: PaymentRequest,
    ): Outcome<SessionChange<Payment>> {
        val signedAmount =
            when (request.kind) {
                PaymentKind.Funding -> request.amount
                else -> -request.amount
            }
        val matching =
            payment.title == request.title &&
                payment.detail == request.detail &&
                payment.amount == signedAmount &&
                payment.kind == request.kind &&
                payment.recurrence == request.recurrence
        if (!matching) return Outcome.Failure(DemoError.RequestConflict)
        val plan = session.plans.find { it.id == request.id }
        if (plan?.date != request.date) return Outcome.Failure(DemoError.RequestConflict)
        return Outcome.Success(SessionChange(session, payment))
    }

    private fun fund(
        session: DemoSession,
        request: PaymentRequest,
    ): Outcome<SessionChange<Payment>> {
        if (session.balance > Long.MAX_VALUE - request.amount)
            return Outcome.Failure(DemoError.InvalidAmount)
        val payment = request.payment(request.amount, PaymentStatus.Successful)
        return Outcome.Success(
            SessionChange(
                session.copy(
                    balance = session.balance + request.amount,
                    payments = listOf(payment) + session.payments,
                ),
                payment,
            ),
        )
    }

    private fun schedule(
        session: DemoSession,
        request: PaymentRequest,
    ): Outcome<SessionChange<Payment>> {
        val date = requireNotNull(request.date)
        val plan =
            BillPlan(
                request.id,
                request.title,
                request.detail,
                request.amount,
                request.kind,
                date,
                recurrence = request.recurrence,
            )
        val payment =
            request
                .payment(-request.amount, PaymentStatus.Scheduled)
                .copy(time = date.format(DateTimeFormatter.ofPattern("d MMM yyyy")))
        return Outcome.Success(
            SessionChange(
                session.copy(
                    plans = session.plans + plan,
                    payments = listOf(payment) + session.payments,
                ),
                payment,
            ),
        )
    }

    /**
     * Funding never implicitly executes waiting requests. Only this explicit action may debit them.
     */
    fun completeWaiting(session: DemoSession, id: String): Outcome<SessionChange<Payment>> {
        val payment =
            session.payments.find { it.id == id } ?: return Outcome.Failure(DemoError.NotFound)
        if (payment.status != PaymentStatus.Waiting)
            return Outcome.Success(SessionChange(session, payment))
        if (session.balance < -payment.amount) return Outcome.Failure(DemoError.InsufficientFunds)
        val completed = payment.copy(status = PaymentStatus.Successful, time = "Just now")
        return Outcome.Success(
            SessionChange(
                session.copy(
                    balance = session.balance + payment.amount,
                    payments =
                        session.payments.map { item ->
                            when (item.id) {
                                id -> completed
                                else -> item
                            }
                        },
                ),
                completed,
            ),
        )
    }

    private fun PaymentRequest.payment(signedAmount: Long, status: PaymentStatus) =
        Payment(id, title, detail, signedAmount, kind, status, recurrence = recurrence)
}
