package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import java.time.Clock
import java.time.LocalDate

/** Observes and restores the current owner; it never triggers payment execution. */
class ObserveSession(private val repository: SessionRepository) {
    /** Observes committed state; callers restore the owner before enabling actions. */
    operator fun invoke() = repository.session

    /** Loads only local state, without executing or reconciling any payment. */
    suspend fun restore(): Outcome<Unit> = repository.initialize()
}

/** Validates individual client terms and delegates atomic local recording to the demo policy. */
class SubmitDemoPayment(
    private val repository: SessionRepository,
    private val prepare: PreparePayment,
    private val clock: Clock,
) {
    /** Main-safe mutation; repository failures preserve the previously committed snapshot. */
    suspend operator fun invoke(input: PaymentInput, requestId: String): Outcome<Payment> =
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

/** Explicitly completes a waiting demo payment when current connectivity permits it. */
class CompleteWaitingPayment(private val repository: SessionRepository) {
    /** Main-safe mutation; repository failures preserve the previously committed snapshot. */
    suspend operator fun invoke(id: String): Outcome<Payment> = repository.transact {
        if (it.offline) return@transact Outcome.Failure(DemoError.OnlineRequired)
        DemoPaymentPolicy.completeWaiting(it, id)
    }
}

/** Changes one existing plan through the shared atomic repository without scheduling execution. */
class SetPlanEnabled(private val repository: SessionRepository) {
    /** Main-safe mutation; repository failures preserve the previously committed snapshot. */
    suspend operator fun invoke(id: String, enabled: Boolean): Outcome<Unit> =
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

/** Persists balance visibility in the owner snapshot without changing funds. */
class SetBalanceVisibility(private val repository: SessionRepository) {
    /** Main-safe mutation; repository failures preserve the previously committed snapshot. */
    suspend operator fun invoke(hidden: Boolean): Outcome<Unit> = repository.transact {
        Outcome.Success(SessionChange(it.copy(hidden = hidden), Unit))
    }
}

/** Persists the demo reminder preference; it does not schedule notifications. */
class SetPaymentReminders(private val repository: SessionRepository) {
    /** Main-safe mutation; repository failures preserve the previously committed snapshot. */
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> = repository.transact {
        Outcome.Success(SessionChange(it.copy(reminderEnabled = enabled), Unit))
    }
}

/** Marks the current demo notifications read through the same session owner. */
class MarkNotificationsRead(private val repository: SessionRepository) {
    /** Main-safe mutation; repository failures preserve the previously committed snapshot. */
    suspend operator fun invoke(): Outcome<Unit> = repository.transact {
        Outcome.Success(SessionChange(it.copy(notificationsRead = true), Unit))
    }
}
