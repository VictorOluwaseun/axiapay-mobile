package com.kesofty.axiapay.presentation.auth

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod

/** Routes for the account-entry prototype; Dashboard denotes demo access, not authentication. */
enum class AuthScreen {
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
}

/**
 * One immutable presentation snapshot. Contact, name, password and code live only in ViewModel
 * memory, never SavedStateHandle. Passwords leave state at verification and completion. Never log
 * this snapshot; its inputs are sensitive even though all operations are currently simulated.
 */
data class AuthUiState(
    val screen: AuthScreen = AuthScreen.Welcome,
    val isCreatingAccount: Boolean = false,
    val contactType: AuthContactType = AuthContactType.Phone,
    val contact: String = "",
    val method: AuthMethod = AuthMethod.OneTimeCode,
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val name: String = "",
    val code: String = "",
    val isProcessing: Boolean = false,
    val error: AuthError? = null,
    val failureCause: Exception? = null,
    val previewReset: Boolean = false,
) {
    /**
     * Keeps diagnostic interpolation from printing transient contact, name, password or code input.
     */
    override fun toString(): String =
        "AuthUiState(screen=$screen, contactType=$contactType, method=$method, isProcessing=$isProcessing, inputs=redacted)"
}

/**
 * Explicit user actions drive navigation and demo operations; rendering never submits a request.
 */
sealed interface AuthIntent {
    /** Opens the form for an existing account. */
    data object SignIn : AuthIntent

    /** Opens signup with its existing name field and the default phone/code method. */
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

    /** Explicitly prepares or completes the current demo form. */
    data object Continue : AuthIntent

    /** Repeats the simulated challenge request and clears the old code. */
    data object ResetCodePreview : AuthIntent

    /** Returns to the preceding screen and cancels any in-flight preview operation. */
    data object Back : AuthIntent

    /** Enters the original dashboard without changing its session or navigation. */
    data object ExploreDemo : AuthIntent

    /** Reopens account-entry design from the demo profile without clearing payment data. */
    data object PreviewAuth : AuthIntent
}
