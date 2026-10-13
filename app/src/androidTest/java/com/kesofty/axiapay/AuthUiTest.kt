package com.kesofty.axiapay

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies account-entry navigation, validation, recreation and preservation of the existing demo.
 */
class AuthUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Waits for the deliberately asynchronous preview adapter to finish before asserting
     * navigation.
     */
    private fun waitForText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Sign-in keeps phone edits, validates code shape and restores the entered demo after
     * recreation.
     */
    @Test
    fun signInValidatesAndOpensTheOriginalWallet() {
        compose.onNodeWithText("I already have an account").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitForText("Enter a valid Nigerian phone number, such as 0801 234 5678.")
        compose.onNodeWithText("Phone number").performScrollTo().performTextInput("08012345678")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitForText("6-digit code")
        compose.onNodeWithText("6-digit code").performTextInput("001")
        compose.onNodeWithText("Verify demo code").performScrollTo().performClick()
        waitForText("Enter all 6 digits to continue.")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("6-digit code").performScrollTo().assertTextContains("001")
        compose.onNodeWithText("Change").performScrollTo().performClick()
        compose.onNodeWithText("Phone number").assertTextContains("08012345678")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitForText("6-digit code")
        compose.onNodeWithText("6-digit code").performTextInput("001234")
        compose.onNodeWithText("Verify demo code").performScrollTo().performClick()
        waitForText("Open demo wallet")
        compose.onNodeWithText("Open demo wallet").performScrollTo().performClick()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
    }

    /**
     * Create-account preview validates its extra field and resets a code without sending an SMS.
     */
    @Test
    fun createAccountAndResendRemainExplicitDemoSteps() {
        compose.onNodeWithText("Get started").performScrollTo().performClick()
        compose.onNodeWithText("Phone number").performScrollTo().performTextInput("+2348012345678")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitForText("Enter at least 2 characters for your name.")
        compose.onNodeWithText("Your name").performScrollTo().performTextInput("Ada")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitForText("6-digit code")
        compose.onNodeWithText("6-digit code").performTextInput("012345")
        compose.onNodeWithText("Try sending a new code").performScrollTo().performClick()
        waitForText("Code preview reset. Enter any 6 digits to try again.")
        compose.onNodeWithText("Verify demo code").performScrollTo().performClick()
        waitForText("Enter all 6 digits to continue.")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Your name").performScrollTo().assertTextContains("Ada")
    }

    /**
     * Revisiting auth leaves funding, saved drafts, connection override and navigation untouched.
     */
    @Test
    fun authPreviewPreservesWalletDraftAndGreyscale() {
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        compose.onNodeWithText("Add money").performClick()
        compose.onNodeWithText("Amount (₦)").performTextInput("5000")
        compose.onNodeWithText("Review top-up").performScrollTo().performClick()
        compose.onNodeWithText("Add demo money").performScrollTo().performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose.onNodeWithText("Force offline").performScrollTo().performClick()
        Espresso.pressBack()
        compose.onNodeWithText("Airtime", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Phone number").performTextInput("08012345678")
        compose.onNodeWithText("Amount (₦)").performTextInput("1000")
        compose.onNodeWithText("Save draft for later").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose.onNodeWithText("Preview welcome & sign-in").performScrollTo().performClick()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        assertTrue(
            (0 until pixels.height step 12).all { y ->
                (0 until pixels.width step 12).all { x ->
                    val color = pixels[x, y]
                    abs(color.red - color.green) < .025f && abs(color.green - color.blue) < .025f
                }
            },
        )
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        compose.onNodeWithText("Force offline").performScrollTo().assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithText("₦133,450.00").assertIsDisplayed()
        compose.onNodeWithText("Manage").performClick()
        compose.onNodeWithText("Continue airtime draft").performScrollTo().performClick()
        compose.onNodeWithText("Phone number").assertTextContains("08012345678")
        compose.onNodeWithText("Amount (₦)").assertTextContains("1000")
    }
}
