package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.DemoAccessInput
import com.kesofty.axiapay.domain.DemoAccessRepository
import kotlinx.coroutines.delay

/**
 * Adds cancellable latency for all auth previews. It accesses no storage/network, retains no input,
 * and never creates an account, compares a stored password, or changes the independent wallet.
 */
class SimulatedAccessRepository : DemoAccessRepository {
    /** Simulates a submitted form; application use cases determine the next demo step. */
    override suspend fun preparePreview(input: DemoAccessInput) {
        delay(450)
    }

    /** Simulates explicit code completion without issuing a token or session. */
    override suspend fun completePreview(code: String) {
        delay(450)
    }

    /** Simulates another code request without sending SMS/email or retaining its destination. */
    override suspend fun resendPreview(contactType: AuthContactType, contact: String) {
        delay(450)
    }
}
