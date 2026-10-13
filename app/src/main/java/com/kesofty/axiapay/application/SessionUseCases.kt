package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import java.time.Clock
import java.time.LocalDate

class ObserveSession(private val repository: SessionRepository) {
    operator fun invoke() = repository.session
}

class SubmitDemoPayment(
    private val repository: SessionRepository,
    private val prepare: PreparePayment,
    private val clock: Clock,
) {
    operator fun invoke(input: PaymentInput, requestId: String): Outcome<Payment> =
        when (val result = prepare(input, requestId)) {
            is Outcome.Failure -> result
            is Outcome.Success ->
                repository.transact {
                    if (it.offline) return@transact Outcome.Failure(DemoError.OnlineRequired)
                    if (it.smsOutbox.any { sms -> sms.id == requestId })
                        return@transact Outcome.Failure(DemoError.SmsAlreadySubmitted)
                    DemoPaymentPolicy.record(it, result.value, LocalDate.now(clock))
                }
        }
}

class CompleteWaitingPayment(private val repository: SessionRepository) {
    operator fun invoke(id: String): Outcome<Payment> = repository.transact {
        if (it.offline) return@transact Outcome.Failure(DemoError.OnlineRequired)
        DemoPaymentPolicy.completeWaiting(it, id)
    }
}

class SetPlanEnabled(private val repository: SessionRepository) {
    operator fun invoke(id: String, enabled: Boolean): Outcome<Unit> =
        repository.transact { session ->
            if (session.offline) return@transact Outcome.Failure(DemoError.OnlineRequired)
            if (session.plans.none { it.id == id })
                return@transact Outcome.Failure(DemoError.NotFound)
            val plans =
                session.plans.map { plan ->
                    when (plan.id) {
                        id -> plan.copy(enabled = enabled)
                        else -> plan
                    }
                }
            Outcome.Success(SessionChange(session.copy(plans = plans), Unit))
        }
}

class SetBalanceVisibility(private val repository: SessionRepository) {
    operator fun invoke(hidden: Boolean): Outcome<Unit> = repository.transact {
        Outcome.Success(SessionChange(it.copy(hidden = hidden), Unit))
    }
}

class SetPaymentReminders(private val repository: SessionRepository) {
    operator fun invoke(enabled: Boolean): Outcome<Unit> = repository.transact {
        Outcome.Success(SessionChange(it.copy(reminderEnabled = enabled), Unit))
    }
}

class MarkNotificationsRead(private val repository: SessionRepository) {
    operator fun invoke(): Outcome<Unit> = repository.transact {
        Outcome.Success(SessionChange(it.copy(notificationsRead = true), Unit))
    }
}

/**
 * One stable logical request identifies the only actionable recipient in this illustrative batch.
 */
class RetryDemoBatchPayment(private val repository: SessionRepository, private val clock: Clock) {
    operator fun invoke(): Outcome<Payment> = repository.transact { session ->
        if (session.offline) return@transact Outcome.Failure(DemoError.OnlineRequired)
        val existing = session.payments.find { it.id == REQUEST_ID }
        if (existing != null)
            return@transact DemoPaymentPolicy.completeWaiting(session, existing.id)
        DemoPaymentPolicy.record(
            session,
            PaymentRequest(
                REQUEST_ID,
                "Team airtime",
                "Demo team batch • Chidi",
                100_000,
                PaymentKind.Bulk,
            ),
            LocalDate.now(clock),
        )
    }

    companion object {
        const val REQUEST_ID = "demo-team-batch-chidi"
    }
}
