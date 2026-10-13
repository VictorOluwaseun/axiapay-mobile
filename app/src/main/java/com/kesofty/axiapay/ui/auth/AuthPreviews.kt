package com.kesofty.axiapay.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthUiState
import com.kesofty.axiapay.ui.theme.AxiapayTheme

/** Provides a welcome canvas in Android Studio using the same production composables. */
@Preview(name = "Welcome", showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun WelcomePreview() {
    AxiapayTheme { AuthScreen(AuthUiState(), offline = false) {} }
}

/** Previews the default phone/code form without a ViewModel or repository. */
@Preview(name = "Sign in", showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun SignInPreview() {
    AxiapayTheme { AuthScreen(AuthUiState(screen = AuthScreen.SignIn), offline = false) {} }
}

/** Makes the initial password-free signup hierarchy reviewable without runtime dependencies. */
@Preview(name = "Sign up", showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun SignupPreview() {
    AxiapayTheme {
        AuthScreen(
            AuthUiState(screen = AuthScreen.CreateAccount, isCreatingAccount = true),
            offline = false,
        ) {}
    }
}

/** Keeps compact, larger-text and offline layouts reviewable alongside the default form. */
@Preview(
    name = "Offline, large text",
    showBackground = true,
    device = "spec:width=360dp,height=800dp,dpi=320",
    fontScale = 1.3f,
)
@Composable
private fun OfflineAuthPreview() {
    AxiapayTheme(offline = true) {
        AuthScreen(
            AuthUiState(screen = AuthScreen.Verification, contact = "0801 234 5678"),
            offline = true,
        ) {}
    }
}

/** Keeps email/password signup reviewable using only synthetic, masked sample input. */
@Preview(
    name = "Email signup with password",
    showBackground = true,
    device = "spec:width=412dp,height=915dp,dpi=420",
)
@Preview(
    name = "Compact signup, large text",
    showBackground = true,
    device = "spec:width=360dp,height=800dp,dpi=320",
    fontScale = 1.3f,
)
@Composable
private fun EmailPasswordSignupPreview() {
    AxiapayTheme {
        AuthScreen(
            AuthUiState(
                screen = AuthScreen.CreateAccount,
                isCreatingAccount = true,
                contactType = AuthContactType.Email,
                contact = "ada@example.com",
                method = AuthMethod.Password,
                name = "Ada",
                password = "demo password",
            ),
            offline = false,
        ) {}
    }
}

/**
 * Exposes the method controls and password form at compact width with larger offline typography.
 */
@Preview(
    name = "Password, offline, large text",
    showBackground = true,
    device = "spec:width=360dp,height=800dp,dpi=320",
    fontScale = 1.3f,
)
@Composable
private fun OfflinePasswordPreview() {
    AxiapayTheme(offline = true) {
        AuthScreen(
            AuthUiState(
                screen = AuthScreen.SignIn,
                contactType = AuthContactType.Email,
                method = AuthMethod.Password,
            ),
            offline = true,
        ) {}
    }
}
