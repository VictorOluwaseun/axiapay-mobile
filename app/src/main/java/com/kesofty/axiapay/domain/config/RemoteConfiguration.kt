package com.kesofty.axiapay.domain.config

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod

/** Immutable public configuration; the version identifies settings, never the installed app. */
data class RemoteConfiguration(
    val version: String,
    val signup: AuthenticationMethods,
    val login: AuthenticationMethods,
    val features: PaymentFeatures = PaymentFeatures.Disabled,
)

/** Availability is advisory for the UI; the API still enforces every authentication request. */
data class AuthenticationMethods(
    val emailEnabled: Boolean,
    val phoneEnabled: Boolean,
    val passwordEnabled: Boolean = true,
    val otpEnabled: Boolean = true,
) {
    /** Resolve a contact without conflating signup policy with login policy. */
    fun allows(contact: AuthContactType): Boolean =
        when (contact) {
            AuthContactType.Email -> emailEnabled
            AuthContactType.Phone -> phoneEnabled
        }

    /**
     * Keep passwordless code availability separate from password signup's mandatory contact proof.
     */
    fun allows(method: AuthMethod): Boolean =
        when (method) {
            AuthMethod.Password -> passwordEnabled
            AuthMethod.OneTimeCode -> otpEnabled
        }

    /**
     * Client availability defaults; these never replace a fetched policy or authorize a request.
     */
    companion object {
        /** Offer email with either credential method until remote switches can be loaded. */
        val EmailOnly =
            AuthenticationMethods(
                emailEnabled = true,
                phoneEnabled = false,
                passwordEnabled = true,
                otpEnabled = true,
            )
    }
}

/** Main-safe access to saved display policy and explicit online revalidation. */
interface RemoteConfigRepository {
    /** Return a validated API-scoped snapshot without network IO, or null when none is usable. */
    suspend fun readCached(): RemoteConfiguration?

    /** Refresh or revalidate all settings atomically; throw on failure, propagate cancellation. */
    suspend fun refresh(): RemoteConfiguration
}

/** Safe failures do not carry provider responses, credentials or cached availability defaults. */
class RemoteConfigFailure(val issue: RemoteConfigIssue) :
    Exception("Configuration unavailable: $issue")

/** Distinguishes recoverable transport, response and atomic persistence failures. */
enum class RemoteConfigIssue {
    Network,
    Configuration,
    Unavailable,
    InvalidResponse,
    Storage,
}
