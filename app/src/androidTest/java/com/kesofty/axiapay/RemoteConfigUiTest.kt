package com.kesofty.axiapay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.domain.auth.AccountAccessMode
import com.kesofty.axiapay.domain.config.*
import com.kesofty.axiapay.presentation.auth.*
import com.kesofty.axiapay.ui.auth.AuthScreen
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Render real authentication policies without changing Firebase or creating any customer accounts.
 */
class RemoteConfigUiTest {
    @get:Rule val compose = createComposeRule()
    private val policy =
        RemoteConfiguration(
            "ui-fixture",
            AuthenticationMethods(true, false),
            AuthenticationMethods(false, true),
        )

    /** Single-method forms show the labeled input directly across policies, palettes and scales. */
    @Test
    fun singleContactFormsOmitSelectorAtLargeFontInBothPalettes() {
        val state = mutableStateOf(AuthUiState(mode = AccountAccessMode.Server))
        val offline = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        compose.setContent {
            AxiapayTheme(offline = offline.value) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, scale.value),
                ) {
                    AuthScreen(state.value, offline.value) {}
                }
            }
        }
        for (palette in listOf(false, true)) {
            for (fontScale in listOf(1f, 1.5f)) {
                for (creating in listOf(false, true)) {
                    for (contactType in AuthContactType.entries) {
                        val methods =
                            AuthenticationMethods(
                                emailEnabled = contactType == AuthContactType.Email,
                                phoneEnabled = contactType == AuthContactType.Phone,
                            )
                        compose.runOnIdle {
                            offline.value = palette
                            scale.value = fontScale
                            state.value =
                                AuthUiState(
                                    screen =
                                        if (creating) AuthScreen.CreateAccount
                                        else AuthScreen.SignIn,
                                    mode = AccountAccessMode.Server,
                                    isCreatingAccount = creating,
                                    contactType = contactType,
                                    configuration = policy.copy(signup = methods, login = methods),
                                )
                        }
                        assertSingleContactCopy(contactType, creating)
                        compose.onNodeWithTag("auth-contact-email").assertDoesNotExist()
                        compose.onNodeWithTag("auth-contact-phone").assertDoesNotExist()
                        compose
                            .onNodeWithTag("auth-contact")
                            .performScrollTo()
                            .assertIsDisplayed()
                            .assertIsEnabled()
                            .assertTextContains(
                                if (contactType == AuthContactType.Email) "Email address"
                                else "Phone number",
                            )
                        compose
                            .onNodeWithTag("auth-method-code")
                            .performScrollTo()
                            .assertIsEnabled()
                        compose.onNodeWithTag("auth-method-password").assertIsEnabled()
                    }
                }
            }
        }
    }

    /** A policy refresh reveals both choices only when the operation permits both contacts. */
    @Test
    fun refreshedPolicyShowsSelectorOnlyForMultipleContacts() {
        val state =
            mutableStateOf(
                AuthUiState(
                    screen = AuthScreen.CreateAccount,
                    mode = AccountAccessMode.Server,
                    isCreatingAccount = true,
                    contactType = AuthContactType.Email,
                    configuration = policy,
                ),
            )
        var intent: AuthIntent? = null
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) { intent = it } } }
        compose.onNodeWithTag("auth-contact-email").assertDoesNotExist()
        compose.onNodeWithTag("auth-contact-phone").assertDoesNotExist()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    configuration = policy.copy(signup = AuthenticationMethods(true, true)),
                )
        }
        compose
            .onNodeWithText("Start with your phone number or email.")
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText("Your account is verified through your phone number or email.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("auth-contact-email").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("auth-contact-phone").assertIsEnabled().performClick()
        assertEquals(AuthIntent.SelectContactType(AuthContactType.Phone), intent)
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    screen = AuthScreen.SignIn,
                    isCreatingAccount = false,
                    contactType = AuthContactType.Phone,
                )
        }
        compose.onNodeWithTag("auth-contact-email").assertDoesNotExist()
        compose.onNodeWithTag("auth-contact-phone").assertDoesNotExist()
        compose.onNodeWithTag("auth-contact").performScrollTo().assertTextContains("Phone number")
    }

    /** A disabled draft has an explicit switch action without a redundant single radio control. */
    @Test
    fun disabledDraftCanSwitchToSoleRemainingContact() {
        val state = mutableStateOf(AuthUiState(mode = AccountAccessMode.Server))
        var intent: AuthIntent? = null
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) { intent = it } } }
        for (creating in listOf(false, true)) {
            for (availableContact in AuthContactType.entries) {
                val methods =
                    AuthenticationMethods(
                        emailEnabled = availableContact == AuthContactType.Email,
                        phoneEnabled = availableContact == AuthContactType.Phone,
                    )
                val disabledContact = AuthContactType.entries.first { it != availableContact }
                compose.runOnIdle {
                    intent = null
                    state.value =
                        AuthUiState(
                            screen = if (creating) AuthScreen.CreateAccount else AuthScreen.SignIn,
                            mode = AccountAccessMode.Server,
                            isCreatingAccount = creating,
                            contactType = disabledContact,
                            contact =
                                if (disabledContact == AuthContactType.Phone) "08012345678"
                                else "person@example.com",
                            configuration = policy.copy(signup = methods, login = methods),
                        )
                }
                compose.onNodeWithTag("auth-contact-email").assertDoesNotExist()
                compose.onNodeWithTag("auth-contact-phone").assertDoesNotExist()
                compose.onNodeWithTag("auth-contact").assertDoesNotExist()
                compose.onNodeWithTag("auth-submit").assertDoesNotExist()
                assertNull(intent)
                compose
                    .onNodeWithTag("auth-contact-switch")
                    .performScrollTo()
                    .assertIsEnabled()
                    .assertTextContains(
                        if (availableContact == AuthContactType.Email) "Use email"
                        else "Use phone number",
                    )
                    .performClick()
                assertEquals(AuthIntent.SelectContactType(availableContact), intent)
            }
        }
    }

    /** Missing config keeps email and both credential methods usable without a blocking notice. */
    @Test
    fun loadingAndFailedConfigKeepEmailPasswordAndCodeEntryAvailable() {
        val state =
            mutableStateOf(
                AuthUiState(
                    screen = AuthScreen.SignIn,
                    mode = AccountAccessMode.Server,
                    isConfigRefreshing = true,
                ),
            )
        var intent: AuthIntent? = null
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) { intent = it } } }
        for (creating in listOf(false, true)) {
            for (loading in listOf(true, false)) {
                compose.runOnIdle {
                    state.value =
                        state.value.copy(
                            screen = if (creating) AuthScreen.CreateAccount else AuthScreen.SignIn,
                            isCreatingAccount = creating,
                            isConfigRefreshing = loading,
                            isConfigUnavailable = !loading,
                            method = com.kesofty.axiapay.domain.AuthMethod.OneTimeCode,
                        )
                }
                assertSingleContactCopy(AuthContactType.Email, creating)
                compose.onNodeWithTag("auth-config-notice").assertDoesNotExist()
                compose.onNodeWithText("Refresh available options").assertDoesNotExist()
                compose.onNodeWithTag("auth-contact-email").assertDoesNotExist()
                compose.onNodeWithTag("auth-contact-phone").assertDoesNotExist()
                compose.onNodeWithTag("auth-contact").performScrollTo().assertIsEnabled()
                compose.onNodeWithTag("auth-method-code").performScrollTo().assertIsEnabled()
                compose.onNodeWithTag("auth-method-password").assertIsEnabled()
                compose
                    .onNodeWithTag("auth-submit")
                    .performScrollTo()
                    .assertIsEnabled()
                    .performClick()
                assertEquals(AuthIntent.Continue, intent)
                compose.runOnIdle {
                    state.value =
                        state.value.copy(method = com.kesofty.axiapay.domain.AuthMethod.Password)
                }
                compose
                    .onNodeWithTag("auth-password")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsEnabled()
                compose.onNodeWithTag("auth-submit").performScrollTo().assertIsEnabled()
            }
        }
    }

    /** A code challenge names its selected delivery contact, even when both methods are enabled. */
    @Test
    fun verificationCopyNamesTheChosenContact() {
        val state = mutableStateOf(AuthUiState(mode = AccountAccessMode.Server))
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) {} } }
        for (creating in listOf(false, true)) {
            for (contactType in AuthContactType.entries) {
                for (bothEnabled in listOf(false, true)) {
                    val methods =
                        AuthenticationMethods(
                            emailEnabled = bothEnabled || contactType == AuthContactType.Email,
                            phoneEnabled = bothEnabled || contactType == AuthContactType.Phone,
                        )
                    compose.runOnIdle {
                        state.value =
                            AuthUiState(
                                screen = AuthScreen.Verification,
                                mode = AccountAccessMode.Server,
                                isCreatingAccount = creating,
                                contactType = contactType,
                                contact =
                                    if (contactType == AuthContactType.Email) "person@example.com"
                                    else "08012345678",
                                configuration = policy.copy(signup = methods, login = methods),
                                codeSecondsRemaining = 60,
                            )
                    }
                    val contactName =
                        if (contactType == AuthContactType.Email) "email address"
                        else "phone number"
                    compose
                        .onNodeWithText("Enter the code sent to your $contactName.")
                        .performScrollTo()
                        .assertIsDisplayed()
                    assertContactNotMentioned(
                        if (contactType == AuthContactType.Email) "phone" else "email",
                    )
                }
            }
        }
    }

    /** Disabled operations do not advertise contacts and retain the alternate account route. */
    @Test
    fun disabledOperationsExplainUnavailabilityWithoutAdvertisingContacts() {
        val state = mutableStateOf(AuthUiState(mode = AccountAccessMode.Server))
        val disabled = AuthenticationMethods(false, false)
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) {} } }
        for (creating in listOf(false, true)) {
            compose.runOnIdle {
                state.value =
                    AuthUiState(
                        screen = if (creating) AuthScreen.CreateAccount else AuthScreen.SignIn,
                        mode = AccountAccessMode.Server,
                        isCreatingAccount = creating,
                        configuration = policy.copy(signup = disabled, login = disabled),
                    )
            }
            compose.onNodeWithText("Please try again later.").performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithText(
                    if (creating)
                        "Account creation is temporarily unavailable. Please try again later."
                    else "Sign-in is temporarily unavailable. Please try again later.",
                )
                .performScrollTo()
                .assertIsDisplayed()
            assertContactNotMentioned("email")
            assertContactNotMentioned("phone")
            compose.onNodeWithTag("auth-submit").assertDoesNotExist()
            compose
                .onNodeWithText(if (creating) "Sign in" else "Create account")
                .performScrollTo()
                .assertIsEnabled()
        }
    }

    /**
     * Only meaningful credential choices render; single-method forms remain usable at compact
     * width.
     */
    @Test
    fun singleCredentialChoicesHideSelectorAtLargeFontInBothPalettes() {
        val state = mutableStateOf(AuthUiState(mode = AccountAccessMode.Server))
        val offline = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        var intent: AuthIntent? = null
        compose.setContent {
            AxiapayTheme(offline = offline.value) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, scale.value),
                ) {
                    Box(Modifier.width(320.dp)) {
                        AuthScreen(state.value, offline.value) { intent = it }
                    }
                }
            }
        }
        for (palette in listOf(false, true)) {
            for (fontScale in listOf(1f, 1.5f)) {
                for (creating in listOf(false, true)) {
                    for (method in AuthMethod.entries) {
                        val methods =
                            AuthenticationMethods(
                                true,
                                false,
                                passwordEnabled = method == AuthMethod.Password,
                                otpEnabled = method == AuthMethod.OneTimeCode,
                            )
                        compose.runOnIdle {
                            offline.value = palette
                            scale.value = fontScale
                            intent = null
                            state.value =
                                AuthUiState(
                                    screen =
                                        if (creating) AuthScreen.CreateAccount
                                        else AuthScreen.SignIn,
                                    mode = AccountAccessMode.Server,
                                    isCreatingAccount = creating,
                                    contactType = AuthContactType.Email,
                                    method = method,
                                    configuration = policy.copy(signup = methods, login = methods),
                                )
                        }
                        compose.onNodeWithTag("auth-method-password").assertDoesNotExist()
                        compose.onNodeWithTag("auth-method-code").assertDoesNotExist()
                        compose
                            .onNodeWithText(if (creating) "Sign up with" else "Sign in with")
                            .assertDoesNotExist()
                        compose.onNodeWithTag("auth-contact").performScrollTo().assertIsEnabled()
                        when (method) {
                            AuthMethod.Password ->
                                compose
                                    .onNodeWithTag("auth-password")
                                    .performScrollTo()
                                    .assertIsDisplayed()
                                    .assertIsEnabled()
                            AuthMethod.OneTimeCode ->
                                compose.onNodeWithTag("auth-password").assertDoesNotExist()
                        }
                        assertNull(intent)
                        compose
                            .onNodeWithTag("auth-submit")
                            .performScrollTo()
                            .assertIsEnabled()
                            .performClick()
                        assertEquals(AuthIntent.Continue, intent)
                    }
                }
            }
        }
    }

    /**
     * A newly disabled drafted credential gets an explicit recovery action without replaying auth.
     */
    @Test
    fun disabledCredentialDraftHasExplicitRecoveryWithoutASingleRadio() {
        val state = mutableStateOf(AuthUiState(mode = AccountAccessMode.Server))
        var intent: AuthIntent? = null
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) { intent = it } } }
        for (creating in listOf(false, true)) {
            for (disabledMethod in AuthMethod.entries) {
                val remaining = AuthMethod.entries.first { it != disabledMethod }
                val methods =
                    AuthenticationMethods(
                        true,
                        false,
                        passwordEnabled = remaining == AuthMethod.Password,
                        otpEnabled = remaining == AuthMethod.OneTimeCode,
                    )
                compose.runOnIdle {
                    intent = null
                    state.value =
                        AuthUiState(
                            screen = if (creating) AuthScreen.CreateAccount else AuthScreen.SignIn,
                            mode = AccountAccessMode.Server,
                            isCreatingAccount = creating,
                            contactType = AuthContactType.Email,
                            contact = "person@example.com",
                            method = disabledMethod,
                            password =
                                if (disabledMethod == AuthMethod.Password)
                                    "private fixture password"
                                else "",
                            configuration = policy.copy(signup = methods, login = methods),
                        )
                }
                compose.onNodeWithTag("auth-method-password").assertDoesNotExist()
                compose.onNodeWithTag("auth-method-code").assertDoesNotExist()
                compose.onNodeWithTag("auth-password").assertDoesNotExist()
                compose.onNodeWithTag("auth-submit").assertDoesNotExist()
                assertNull(intent)
                compose
                    .onNodeWithTag("auth-method-switch")
                    .performScrollTo()
                    .assertIsEnabled()
                    .assertTextContains(
                        if (remaining == AuthMethod.Password) "Use password"
                        else "Use one-time code",
                    )
                    .performClick()
                assertEquals(AuthIntent.SelectMethod(remaining), intent)
            }
        }
    }

    /**
     * Enabled contacts do not advertise a usable form when neither credential method is available.
     */
    @Test
    fun bothCredentialChoicesDisabledExplainUnavailability() {
        val state = mutableStateOf(AuthUiState(mode = AccountAccessMode.Server))
        val disabled = AuthenticationMethods(true, true, false, false)
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) {} } }
        for (creating in listOf(false, true)) {
            compose.runOnIdle {
                state.value =
                    AuthUiState(
                        screen = if (creating) AuthScreen.CreateAccount else AuthScreen.SignIn,
                        mode = AccountAccessMode.Server,
                        isCreatingAccount = creating,
                        configuration = policy.copy(signup = disabled, login = disabled),
                    )
            }
            compose.onNodeWithTag("auth-config-notice").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("auth-contact").assertDoesNotExist()
            compose.onNodeWithTag("auth-method-password").assertDoesNotExist()
            compose.onNodeWithTag("auth-method-code").assertDoesNotExist()
            compose.onNodeWithTag("auth-submit").assertDoesNotExist()
            assertContactNotMentioned("email")
            assertContactNotMentioned("phone")
        }
    }

    /**
     * Password signup's mandatory proof is independent of passwordless OTP signup/login controls.
     */
    @Test
    fun verificationFollowsTheOriginalCredentialMethod() {
        val state =
            mutableStateOf(
                AuthUiState(
                    screen = AuthScreen.Verification,
                    mode = AccountAccessMode.Server,
                    contactType = AuthContactType.Email,
                    contact = "person@example.com",
                    method = AuthMethod.OneTimeCode,
                    configuration =
                        policy.copy(login = AuthenticationMethods(true, true, true, false)),
                    codeSecondsRemaining = 60,
                ),
            )
        compose.setContent { AxiapayTheme { AuthScreen(state.value, false) {} } }
        compose.onNodeWithTag("auth-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Send a new code").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    isCreatingAccount = true,
                    method = AuthMethod.Password,
                    configuration =
                        policy.copy(signup = AuthenticationMethods(true, true, true, false)),
                )
        }
        compose.onNodeWithTag("auth-config-notice").assertDoesNotExist()
        compose.onNodeWithTag("auth-submit").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Send a new code").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    configuration =
                        policy.copy(signup = AuthenticationMethods(true, true, false, true)),
                )
        }
        compose.onNodeWithTag("auth-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Send a new code").performScrollTo().assertIsNotEnabled()
    }

    /** Match both introductory and supporting copy to the operation's sole enabled contact. */
    private fun assertSingleContactCopy(contactType: AuthContactType, creating: Boolean) {
        val contactName =
            if (contactType == AuthContactType.Email) "email address" else "phone number"
        val introduction =
            if (creating) "Start with your $contactName." else "Sign in with your $contactName."
        compose.onNodeWithText(introduction).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Your account is verified through your $contactName.")
            .performScrollTo()
            .assertIsDisplayed()
        assertContactNotMentioned(if (contactType == AuthContactType.Email) "phone" else "email")
    }

    /** Inspect the entire semantic tree, including copy outside the current viewport. */
    private fun assertContactNotMentioned(contactName: String) {
        compose
            .onAllNodes(hasText(contactName, substring = true, ignoreCase = true))
            .assertCountEquals(0)
    }
}
