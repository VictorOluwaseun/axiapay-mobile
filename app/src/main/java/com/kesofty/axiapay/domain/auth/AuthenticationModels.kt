package com.kesofty.axiapay.domain.auth

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.domain.wallet.WalletLookupResult
import java.time.Instant

/** Selects real authentication or the permanently separate demonstration at composition time. */
enum class AccountAccessMode {
    Demo,
    Server,
}

/** A normalized API contact; never interpolate customer identity into diagnostics. */
data class AccountContact(val kind: AuthContactType, val value: String) {
    /** Redacts all identifying or credential-bearing fields from diagnostic interpolation. */
    override fun toString() = "AccountContact(kind=$kind, value=redacted)"
}

/** Transient form submission; passwords remain exact and no field may enter saved UI state. */
data class AccountAccessInput(
    val contactType: AuthContactType,
    val contact: String,
    val method: AuthMethod,
    val password: String = "",
    val name: String = "",
    val isCreatingAccount: Boolean = false,
) {
    /** Redacts all identifying or credential-bearing fields from diagnostic interpolation. */
    override fun toString() =
        "AccountAccessInput(method=$method, signup=$isCreatingAccount, fields=redacted)"
}

/** Distinguishes server endpoints; a challenge must never cross from signup to login. */
enum class AuthenticationPurpose {
    Signup,
    Login,
}

/** Relative timing returned by the API; the server remains authoritative for expiry and retries. */
data class AuthenticationChallengeTerms(
    val id: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
)

/** An in-memory, purpose-bound challenge. No password or verification code is retained here. */
data class AuthenticationChallenge(
    val id: String,
    val purpose: AuthenticationPurpose,
    val contact: AccountContact,
    val usesPassword: Boolean,
    val expiresAt: Instant,
    val resendAt: Instant,
) {
    /** Redacts all identifying or credential-bearing fields from diagnostic interpolation. */
    override fun toString() = "AuthenticationChallenge(purpose=$purpose, fields=redacted)"
}

/** Verified identity returned by the API; it grants no wallet or payment permissions itself. */
data class AccountIdentity(val id: String, val contact: AccountContact, val verifiedAt: Instant) {
    /** Redacts all identifying or credential-bearing fields from diagnostic interpolation. */
    override fun toString() = "AccountIdentity(fields=redacted)"
}

/** Opaque bearer credentials stay outside presentation state and use protected local storage. */
data class AuthenticatedSession(
    val accessToken: String,
    val expiresAt: Instant,
    val account: AccountIdentity,
) {
    /** Redacts all identifying or credential-bearing fields from diagnostic interpolation. */
    override fun toString() = "AuthenticatedSession(credentials=redacted)"
}

/** One protected record scoped to the configured API, never a saved-state or backup payload. */
data class StoredAuthenticationSession(
    val accessToken: String,
    val expiresAt: Instant,
    val apiIdentity: String,
    val accountId: String? = null,
) {
    /** Redacts all identifying or credential-bearing fields from diagnostic interpolation. */
    override fun toString() = "StoredAuthenticationSession(credentials=redacted)"
}

/** Safe client feedback; generic credential errors must not disclose whether an account exists. */
enum class AccountAccessIssue {
    Phone,
    Email,
    Name,
    Password,
    Code,
    InvalidCredentials,
    CodeRejected,
    CodeExpired,
    RateLimited,
    Network,
    Unavailable,
    InvalidResponse,
    InvalidRequest,
    Configuration,
    SecureStorage,
    SignupPasswordRequired,
    MethodDisabled,
}

/** Carries only allowlisted failure information; never include response bodies or credentials. */
class AuthenticationFailure(
    val issue: AccountAccessIssue,
    val retryAfterSeconds: Long = 0,
    val requestReference: String? = null,
) : Exception("Authentication failed: $issue")

/** An explicit application result; demo completion is distinct from a verified server identity. */
sealed interface AccountAccessResult {
    /** Carries server proof metadata; a null challenge belongs only to demo mode. */
    data class CodeRequired(val challenge: AuthenticationChallenge? = null) : AccountAccessResult

    /**
     * Exposes verified identity and wallet display outcome while credentials remain in protected
     * storage.
     */
    data class SignedIn(val account: AccountIdentity, val wallet: WalletLookupResult? = null) :
        AccountAccessResult

    /** Provides safe recoverable feedback without an identity or credential value. */
    data class Rejected(
        val issue: AccountAccessIssue,
        val retryAfterSeconds: Long = 0,
        val requestReference: String? = null,
    ) : AccountAccessResult

    /** Signals only simulated completion, which must never authorize real account access. */
    data object DemoComplete : AccountAccessResult

    /** Indicates no protected session remains available to this client. */
    data object SignedOut : AccountAccessResult
}
