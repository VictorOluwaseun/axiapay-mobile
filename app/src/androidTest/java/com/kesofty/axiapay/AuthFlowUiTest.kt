package com.kesofty.axiapay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Exercises each contact/method/signup preview through the installed demo flavor's UI and Hilt
 * graph.
 */
@RunWith(Parameterized::class)
class AuthFlowUiTest(
    private val contactType: AuthContactType,
    private val method: AuthMethod,
    private val isCreatingAccount: Boolean,
) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Runs simulation assertions only in demo; ServerAuthFlowUiTest checks the real API contract.
     */
    @Before
    fun requireDemoAuthentication() {
        Assume.assumeTrue(BuildConfig.FLAVOR == "demo")
    }

    /**
     * Enumerates all eight supported previews without changing a flavor or injecting test bindings.
     */
    companion object {
        /** Supplies independent Activity/test lifetimes for each route. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, {1}, signup={2}")
        fun authCases(): List<Array<Any>> =
            AuthContactType.entries.flatMap { contact ->
                AuthMethod.entries.flatMap { method ->
                    listOf(false, true).map { signup -> arrayOf(contact, method, signup) }
                }
            }
    }

    /** Waits for cancellable simulated latency before asserting the resulting route. */
    private fun waitForText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Verifies explicit selection, password masking, retained form state, signup/code verification,
     * direct password sign-in, and entry into the unchanged sample wallet.
     */
    @Test
    fun selectedAuthFlowCompletesIntoTheDemoWallet() {
        compose
            .onNodeWithText(if (isCreatingAccount) "Get started" else "I already have an account")
            .performScrollTo()
            .performClick()
        if (contactType == AuthContactType.Email) {
            compose.onNodeWithTag("auth-contact-email").performScrollTo().performClick()
        }
        if (isCreatingAccount) {
            compose.onNodeWithTag("auth-name").performScrollTo().performTextInput("Ada")
        }
        val contact =
            if (contactType == AuthContactType.Phone) "08012345678" else "Ada+demo@example.com"
        compose.onNodeWithTag("auth-contact").performScrollTo().performTextInput(contact)
        if (method == AuthMethod.Password) {
            compose.onNodeWithTag("auth-method-password").performScrollTo().performClick()
            compose
                .onNodeWithTag("auth-password")
                .performScrollTo()
                .performTextInput("demo password")
            compose
                .onNodeWithTag("auth-password")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            compose.onNodeWithContentDescription("Show password").performClick()
            compose.onNodeWithContentDescription("Hide password").assertIsDisplayed().performClick()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("auth-contact").performScrollTo().assertTextContains(contact)
        compose
            .onNodeWithTag(
                if (method == AuthMethod.Password) "auth-method-password" else "auth-method-code",
            )
            .performScrollTo()
            .assertIsSelected()
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        val requiresCode = isCreatingAccount || method == AuthMethod.OneTimeCode
        if (requiresCode) {
            waitForText("6-digit code")
            compose.onNodeWithText(contact).assertIsDisplayed()
            compose.onNodeWithTag("auth-password").assertDoesNotExist()
            compose.onNodeWithTag("auth-code").performScrollTo().performTextInput("001234")
            compose.onNodeWithTag("auth-code").assertTextContains("001234")
            // Done is an explicit user submission; it avoids coordinate taps during IME motion.
            compose.onNodeWithTag("auth-code").performImeAction()
        }
        waitForText("Open demo wallet")
        compose.onNodeWithTag("auth-code").assertDoesNotExist()
        compose.onNodeWithTag("auth-password").assertDoesNotExist()
        compose.onNodeWithText("Open demo wallet").performScrollTo().performClick()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
    }
}
