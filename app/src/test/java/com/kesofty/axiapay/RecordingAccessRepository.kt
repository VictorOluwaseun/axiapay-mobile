package com.kesofty.axiapay

import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.CompletableDeferred

/**
 * Records synthetic test inputs and controlled failures only; no storage, delivery or auth is used.
 */
internal class RecordingAccessRepository : DemoAccessRepository {
    var preparations = 0
    var resends = 0
    var lastInput: DemoAccessInput? = null
    var lastCode: String? = null
    var lastResendContact: String? = null
    var gate: CompletableDeferred<Unit>? = null
    var exception: Exception? = null

    /**
     * Records the exact normalized form and waits for the test's completion/cancellation decision.
     */
    override suspend fun preparePreview(input: DemoAccessInput) {
        preparations += 1
        lastInput = input
        awaitResult()
    }

    /** Records exact code characters so leading-zero preservation can be verified. */
    override suspend fun completePreview(code: String) {
        lastCode = code
        awaitResult()
    }

    /** Records a separate resend operation, independent of the original form and password. */
    override suspend fun resendPreview(contactType: AuthContactType, contact: String) {
        resends += 1
        lastResendContact = contact
        awaitResult()
    }

    /** Exposes a cancellable boundary and a failure with its original context. */
    private suspend fun awaitResult() {
        gate?.await()
        exception?.let { throw it }
    }
}
