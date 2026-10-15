package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** Deliberately small sample eligibility list; actual provider support is undecided. */
object DemoSmsEligibility {
    fun supports(input: PaymentInput): Boolean =
        input.type in setOf(PaymentType.Airtime, PaymentType.Data) && !input.scheduled
}

class PrepareSmsPayment(
    private val repository: SessionRepository,
    private val prepare: PreparePayment,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: PaymentInput, id: String, sim: Int): Outcome<SmsRequest> {
        if (!DemoSmsEligibility.supports(input)) return Outcome.Failure(DemoError.SmsUnsupported)
        if (sim !in 1..2) return Outcome.Failure(DemoError.SmsUnsupported)
        return when (val result = prepare(input, id)) {
            is Outcome.Failure -> result
            is Outcome.Success ->
                repository.transact { session ->
                    DemoSmsPolicy.save(
                        session,
                        SmsRequest(
                            result.value,
                            sim,
                            clock.instant(),
                            clock.instant().plus(Duration.ofMinutes(15)),
                        ),
                    )
                }
        }
    }
}

/** Deterministic transport simulation only. No SMS API, permission, phone number or secret. */
class SendDemoSms(private val repository: SessionRepository, private val clock: Clock) {
    suspend operator fun invoke(id: String, simulateFailure: Boolean): Outcome<SmsRequest> =
        repository.transact {
            DemoSmsPolicy.send(it, id, clock.instant(), simulateFailure)
        }
}

class ReconcileDemoSms(private val repository: SessionRepository, private val clock: Clock) {
    suspend operator fun invoke(id: String): Outcome<Payment> = repository.transact {
        DemoSmsPolicy.confirm(it, id, LocalDate.now(clock))
    }
}
