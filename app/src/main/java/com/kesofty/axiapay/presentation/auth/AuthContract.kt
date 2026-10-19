package com.kesofty.axiapay.presentation.auth

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.domain.auth.AccountAccessMode
import com.kesofty.axiapay.domain.auth.AccountIdentity
import com.kesofty.axiapay.domain.auth.AuthenticationChallenge
import com.kesofty.axiapay.domain.config.AuthenticationMethods
import com.kesofty.axiapay.domain.config.RemoteConfiguration
import com.kesofty.axiapay.domain.wallet.WalletLookupResult
import com.kesofty.axiapay.presentation.HomeWalletState

/** Account routes; Dashboard selects its wallet source from the injected account workflow. */
enum class AuthScreen {
    Restoring,
    SessionRecovery,
    Welcome,
    SignIn,
    CreateAccount,
    Verification,
    Complete,
    Dashboard,
}

/** Inline validation and recoverable operation errors are rendered from state. */
enum class AuthError {
    Phone,
    Email,
    Name,
    Password,
    Code,
    Unavailable,
    Credentials,
    CodeRejected,
    CodeExpired,
    RateLimited,
    Network,
    Configuration,
    InvalidRequest,
    SecureStorage,
    PasswordAgain,
    MethodDisabled,
}

/**
 * One immutable presentation snapshot. Contact, name, password and code live only in ViewModel
 * memory, never SavedStateHandle. Passwords leave state at verification and completion. Never log
 * this snapshot; its inputs are sensitive even though demo completion stays distinct from server
 * authentication.
 */
data class AuthUiState(
    val screen: AuthScreen = AuthScreen.Welcome,
    val mode: AccountAccessMode = AccountAccessMode.Demo,
    val account: AccountIdentity? = null,
    val wallet: WalletLookupResult? = null,
    val challenge: AuthenticationChallenge? = null,
    val configuration: RemoteConfiguration? = null,
    val isConfigRefreshing: Boolean = false,
    val isConfigUnavailable: Boolean = false,
    val codeSecondsRemaining: Long = 0,
    val resendSecondsRemaining: Long = 0,
    val retryAfterSeconds: Long = 0,
    val requestReference: String? = null,
    val isCreatingAccount: Boolean = false,
    val contactType: AuthContactType =
        if (mode == AccountAccessMode.Server && configuration == null) AuthContactType.Email
        else AuthContactType.Phone,
    val contact: String = "",
    val method: AuthMethod = AuthMethod.OneTimeCode,
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val name: String = "",
    val code: String = "",
    val isProcessing: Boolean = false,
    val isWalletRefreshing: Boolean = false,
    val error: AuthError? = null,
    val failureCause: Exception? = null,
    val isCodeResent: Boolean = false,
) {
    /**
     * Demo availability is selected explicitly; real unknown policy keeps new features unavailable.
     */
    val paymentFeatures: com.kesofty.axiapay.domain.config.PaymentFeatures
        get() =
            if (isDemo) com.kesofty.axiapay.domain.config.PaymentFeatures.AllEnabled
            else
                configuration?.features
                    ?: com.kesofty.axiapay.domain.config.PaymentFeatures.Disabled

    /** Project separate signup/login switches into the caller-owned contact selector. */
    val availableContacts: List<AuthContactType>
        get() = AuthContactType.entries.filter { isContactEnabled(it) }

    /** Project separate signup/login password and passwordless-code switches. */
    val availableMethods: List<AuthMethod>
        get() = AuthMethod.entries.filter { isMethodEnabled(it) }

    /** A usable form needs at least one contact and one credential choice. */
    val hasAvailableAuthentication: Boolean
        get() = availableContacts.isNotEmpty() && availableMethods.isNotEmpty()

    /** Both selected choices must still be available before submitting or resending proof. */
    val isSelectedAuthenticationEnabled: Boolean
        get() = isContactEnabled(contactType) && isMethodEnabled(method)

    /**
     * Keep saved switches during failures; initial client defaults allow email with either method.
     */
    private val authenticationMethods: AuthenticationMethods
        get() =
            (if (isCreatingAccount) configuration?.signup else configuration?.login)
                ?: AuthenticationMethods.EmailOnly

    /**
     * Demo always exposes its fixture; real contact choices follow the operation's saved policy.
     */
    fun isContactEnabled(contact: AuthContactType): Boolean =
        isDemo || authenticationMethods.allows(contact)

    /** Password signup's proof remains usable when only the passwordless OTP option is disabled. */
    fun isMethodEnabled(method: AuthMethod): Boolean =
        isDemo || authenticationMethods.allows(method)

    /** Copy and controls follow the injected workflow, never a flavor check inside a View. */
    val isDemo: Boolean
        get() = mode == AccountAccessMode.Demo

    /**
     * Project account-owned wallet state without ever consulting the demo ledger in server mode.
     */
    val homeWallet: HomeWalletState
        get() =
            when {
                isDemo -> HomeWalletState.Demo
                account == null -> HomeWalletState.SignedOut
                isWalletRefreshing -> HomeWalletState.Loading
                wallet is WalletLookupResult.Loaded -> HomeWalletState.Loaded(wallet.wallet)
                wallet is WalletLookupResult.Failed -> HomeWalletState.Failed(wallet.issue)
                else -> HomeWalletState.Loading
            }

    /**
     * Keeps diagnostic interpolation from printing transient contact, name, password or code input.
     */
    override fun toString(): String =
        "AuthUiState(screen=$screen, contactType=$contactType, method=$method, isProcessing=$isProcessing, inputs=redacted)"
}

/**
 * Explicit user actions drive navigation and account operations; rendering never submits a request.
 */
sealed interface AuthIntent {
    /** Rechecks a stored session after foregrounding without replaying pending form operations. */
    data object RefreshSession : AuthIntent

    /** Explicitly retry availability reads without replaying account creation or login. */
    data object RetryConfiguration : AuthIntent

    /** Explicitly retries a failed startup/foreground session check. */
    data object RetrySession : AuthIntent

    /**
     * Revalidates the stored session and retries only a wallet read, never login or code
     * redemption.
     */
    data object RetryWallet : AuthIntent

    /** Revokes the real bearer session before removing protected local credentials. */
    data object SignOut : AuthIntent

    /** Opens the form for an existing account. */
    data object SignIn : AuthIntent

    /**
     * Opens signup with configured choices or the email/code default; only demo collects a preview
     * name.
     */
    data object CreateAccount : AuthIntent

    /** Selects phone or email and clears the previous contact and credentials. */
    data class SelectContactType(val contactType: AuthContactType) : AuthIntent

    /** Selects password or code without submitting; switching clears any password/code. */
    data class SelectMethod(val method: AuthMethod) : AuthIntent

    /** Changes the selected contact field without starting an operation. */
    data class EditContact(val value: String) : AuthIntent

    /** Changes exact password characters in the password form only. */
    data class EditPassword(val value: String) : AuthIntent

    /** Explicitly shows/hides the password without changing its value or submitting it. */
    data object TogglePasswordVisibility : AuthIntent

    /** Changes the name field for the create-account preview. */
    data class EditName(val value: String) : AuthIntent

    /** Changes the single accessible code field without auto-submitting it. */
    data class EditCode(val value: String) : AuthIntent

    /** Explicitly prepares the current account form or verifies its captured challenge/code. */
    data object Continue : AuthIntent

    /** Requests a replacement purpose-bound challenge and clears the old code. */
    data object ResendCode : AuthIntent

    /** Returns to the preceding screen and cancels its owned account operation. */
    data object Back : AuthIntent

    /** Enters the original dashboard without changing its session or navigation. */
    data object ExploreDemo : AuthIntent

    /**
     * Enter Home with the current verified wallet while retaining the independent demo payment
     * owner.
     */
    data object OpenDashboard : AuthIntent

    /** Opens the current real account or demo entry screen without clearing payment data. */
    data object OpenAccount : AuthIntent
}
