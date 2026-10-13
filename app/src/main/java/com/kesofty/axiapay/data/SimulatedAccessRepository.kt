package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.DemoAccessRepository
import com.kesofty.axiapay.domain.DemoAccessResult
import kotlinx.coroutines.delay

/**
 * Adds a small cancellable delay for the auth prototype. It reads/writes no storage or network,
 * retains no phone numbers or codes, and has no cache, transaction, or wallet side effects.
 */
class SimulatedAccessRepository : DemoAccessRepository {
    /** Simulates preparing a challenge without contacting an SMS service. */
    override suspend fun preparePreview(phoneNumber: String): DemoAccessResult {
        delay(450)
        return DemoAccessResult.PreviewReady
    }

    /** Simulates completing the preview without issuing a session or token. */
    override suspend fun completePreview(code: String): DemoAccessResult {
        delay(450)
        return DemoAccessResult.PreviewReady
    }
}
