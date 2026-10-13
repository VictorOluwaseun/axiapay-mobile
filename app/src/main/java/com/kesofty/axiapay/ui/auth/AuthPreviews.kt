package com.kesofty.axiapay.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthUiState
import com.kesofty.axiapay.ui.theme.AxiapayTheme

/** Provides a welcome canvas in Android Studio using the same production composables. */
@Preview(name = "Welcome", showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun WelcomePreview() {
    AxiapayTheme { AuthScreen(AuthUiState(), offline = false) {} }
}

/** Previews the phone form at the common handset size without a ViewModel or repository. */
@Preview(name = "Sign in", showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun SignInPreview() {
    AxiapayTheme { AuthScreen(AuthUiState(screen = AuthScreen.SignIn), offline = false) {} }
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
            AuthUiState(screen = AuthScreen.Verification, phoneNumber = "0801 234 5678"),
            offline = true,
        ) {}
    }
}
