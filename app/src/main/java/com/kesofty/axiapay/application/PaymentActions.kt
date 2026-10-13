package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*

/**
 * Submits reviewed terms using a stable logical request ID; implementations must be main-safe. Demo
 * wiring delegates to local rules. Future server implementations own authoritative outcomes,
 * preserve idempotency and return an explicit uncertain result when confirmation is unavailable.
 */
fun interface SubmitPayment {
    /** Returns the recorded payment or an explicit error; cancellation must propagate. */
    suspend operator fun invoke(input: PaymentInput, requestId: String): Outcome<Payment>
}

/** Explicitly resumes a waiting payment; never runs automatically after funding or reconnecting. */
fun interface CompletePayment {
    /** Completes the existing ID with main-safe work; preserves cancellation and domain errors. */
    suspend operator fun invoke(paymentId: String): Outcome<Payment>
}

/** Retries the existing actionable batch request without resubmitting completed recipients. */
fun interface RetryBatchPayment {
    /** Returns the recorded attempt through main-safe, cancellable work. */
    suspend operator fun invoke(): Outcome<Payment>
}

/** Changes an existing plan's enabled state without executing a scheduled payment. */
fun interface UpdatePlan {
    /** Persists the requested value with main-safe work; propagates caller cancellation. */
    suspend operator fun invoke(planId: String, enabled: Boolean): Outcome<Unit>
}

/** Prepares reviewed SMS terms under the same logical identity as the online payment path. */
fun interface PrepareSmsRequest {
    /** Saves an unsent request; SIM is the selected demo slot. Does not send or debit funds. */
    suspend operator fun invoke(
        input: PaymentInput,
        requestId: String,
        sim: Int,
    ): Outcome<SmsRequest>
}

/** Simulates SMS transport only; transport evidence is never a confirmed payment outcome. */
fun interface SendSmsRequest {
    /** Performs a main-safe demo attempt, preserving request identity and caller cancellation. */
    suspend operator fun invoke(requestId: String, simulateFailure: Boolean): Outcome<SmsRequest>
}

/** Requests a result for an existing SMS request; only confirmed outcomes may change funds. */
fun interface ReconcileSmsRequest {
    /**
     * Uses main-safe work and preserves cancellation; the current implementation is a simulation.
     */
    suspend operator fun invoke(requestId: String): Outcome<Payment>
}
