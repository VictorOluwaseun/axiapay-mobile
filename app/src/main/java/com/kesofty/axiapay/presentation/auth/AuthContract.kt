package com.kesofty.axiapay.presentation.auth

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
    Name,
    Code,
    Unavailable,
}

/**
 * One immutable presentation snapshot. Phone, name and code live only in ViewModel memory, never
 * SavedStateHandle. Codes are cleared when leaving verification, on success, and on resend.
 */
data class AuthUiState(
    val screen: AuthScreen = AuthScreen.Welcome,
    val isCreatingAccount: Boolean = false,
    val phoneNumber: String = "",
    val name: String = "",
    val code: String = "",
    val isProcessing: Boolean = false,
    val error: AuthError? = null,
    val failureCause: Exception? = null,
    val previewReset: Boolean = false,
)

/**
 * Explicit user actions drive navigation and demo operations; rendering never submits a request.
 */
sealed interface AuthIntent {
    /** Opens the form for an existing account. */
    data object SignIn : AuthIntent

    /** Opens the optional name and phone form for a new account. */
    data object CreateAccount : AuthIntent

    /** Changes the phone field without starting a challenge. */
    data class EditPhone(val value: String) : AuthIntent

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
