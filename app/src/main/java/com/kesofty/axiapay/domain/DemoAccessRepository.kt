package com.kesofty.axiapay.domain

/** Identifies the contact format selected explicitly by the customer. */
enum class AuthContactType {
    Phone,
    Email,
}

/** Selects password or one-time-code entry; signup verifies contact in either workflow. */
enum class AuthMethod {
    Password,
    OneTimeCode,
}

/** Application outcomes describe the next demo step, never ownership or payment authorization. */
enum class DemoAccessResult {
    CodeRequired,
    PreviewComplete,
    InvalidPhone,
    InvalidEmail,
    InvalidName,
    InvalidPassword,
    InvalidCode,
}

/**
 * Captures one explicit form submission. Contact normalization belongs to the application layer;
 * password characters must remain exact. Inputs are transient and must never be saved or logged.
 */
data class DemoAccessInput(
    val contactType: AuthContactType,
    val contact: String,
    val method: AuthMethod,
    val password: String = "",
    val name: String = "",
    val isCreatingAccount: Boolean = false,
) {
    /** Prevents accidental diagnostic interpolation from exposing contact or credential values. */
    override fun toString(): String =
        "DemoAccessInput(contactType=$contactType, method=$method, isCreatingAccount=$isCreatingAccount, inputs=redacted)"
}

/**
 * Simulates account-entry operations without storing credentials, sending SMS/email, or creating
 * accounts. The application owns validation and next-step decisions. Implementations are main-safe,
 * propagate cancellation/failure, and never change the payment session. No cache or transaction is
 * used.
 */
interface DemoAccessRepository {
    /** Previews a validated submission; returning normally establishes no identity or session. */
    suspend fun preparePreview(input: DemoAccessInput)

    /** Previews completion using exact code characters, including leading zeros. */
    suspend fun completePreview(code: String)

    /**
     * Resets the code preview for an existing destination without needing the original password.
     */
    suspend fun resendPreview(contactType: AuthContactType, contact: String)
}
