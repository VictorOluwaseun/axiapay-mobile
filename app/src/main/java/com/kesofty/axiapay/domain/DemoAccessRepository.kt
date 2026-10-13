package com.kesofty.axiapay.domain

/** Demo access outcomes describe a preview, never verified ownership or payment authorization. */
enum class DemoAccessResult {
    PreviewReady,
    InvalidPhone,
    InvalidName,
    InvalidCode,
}

/**
 * Simulates account-entry requests without storing credentials, sending SMS, or creating accounts.
 * Implementations must be main-safe and propagate cancellation. No payment session is modified.
 */
interface DemoAccessRepository {
    /** Prepares the code-entry preview for a normalized Nigerian phone number. */
    suspend fun preparePreview(phoneNumber: String): DemoAccessResult

    /** Completes a preview using any six ASCII digits; this does not authenticate a person. */
    suspend fun completePreview(code: String): DemoAccessResult
}
