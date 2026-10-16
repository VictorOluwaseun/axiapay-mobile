package com.kesofty.axiapay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AxiaUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Runs simulated wallet assertions in demo through the public welcome action. */
    @Before
    fun enterDemo() {
        Assume.assumeTrue(
            "Simulated wallet flows require the demo flavor",
            BuildConfig.FLAVOR == "demo",
        )
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
    }

    @Test
    fun fundingUpdatesWalletAndSurvivesActivityRecreation() {
        compose.onNodeWithText("Add money").performClick()
        compose.onNodeWithText("Amount (₦)").performTextInput("5000")
        compose.onNodeWithText("Review top-up").performScrollTo().performClick()
        compose.onNodeWithText("Add demo money").performScrollTo().performClick()
        compose.onNodeWithText("All taken care of.").assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("₦133,450.00").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("₦133,450.00").assertIsDisplayed()
    }

    @Test
    fun invalidRecipientCannotPayAndValidPaymentCreatesReceipt() {
        compose.onNodeWithText("Airtime", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Phone number").performTextInput("123")
        compose.onNodeWithText("Amount (₦)").performTextInput("1000")
        compose.onNodeWithText("Review payment").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithText("Phone number")
            .performScrollTo()
            .performTextReplacement("08000000000")
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("Confirm demo payment").performScrollTo().performClick()
        compose.onNodeWithText("All taken care of.").assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("₦127,450.00").assertIsDisplayed()
    }

    @Test
    fun schedulesDoNotDebitAndBalanceVisibilityCanBeToggled() {
        compose.onNodeWithText("Plans").performClick()
        compose.onNodeWithText("Schedule a payment").performClick()
        compose.onNodeWithText("Phone number").performTextInput("08000000000")
        compose.onNodeWithText("Amount (₦)").performTextInput("1000")
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("Save demo schedule").performScrollTo().performClick()
        compose.onNodeWithText("Scheduled").assertExists()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide balance").performClick()
        compose.onNodeWithText("₦128,450.00").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show balance").performClick()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
    }

    @Test
    fun recurringPaymentSurvivesRecreationAndCanBePaused() {
        compose.onNodeWithText("Plans").performClick()
        compose.onNodeWithText("Schedule a payment").performClick()
        compose.onNodeWithText("Phone number").performTextInput("08000000000")
        compose.onNodeWithText("Amount (₦)").performTextInput("1000")
        compose.onNodeWithTag("repeat-frequency").performScrollTo()
        compose.onNodeWithText("Weekly").performClick()
        compose.onNodeWithText("Next payments").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("Weekly").assertExists()
        compose.onNodeWithText("Save demo schedule").performScrollTo().performClick()
        compose.onNodeWithText("Repeats").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Weekly").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Weekly").assertExists()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose
            .onNodeWithTag("plans-list")
            .performScrollToNode(hasContentDescription("Enable MTN Airtime"))
        compose.onNodeWithContentDescription("Enable MTN Airtime").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Enable MTN Airtime").assertIsOff()
        compose.activityRule.scenario.recreate()
        compose
            .onNodeWithTag("plans-list")
            .performScrollToNode(hasContentDescription("Enable MTN Airtime"))
        compose.onNodeWithContentDescription("Enable MTN Airtime").assertIsOff()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
    }

    @Test
    fun waitingPaymentRequiresExplicitCompletionAfterFunding() {
        compose.onNodeWithText("Airtime", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Phone number").performTextInput("08000000000")
        compose.onNodeWithText("Amount (₦)").performTextInput("130000")
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("Save until funded").performScrollTo().performClick()
        compose.onNode(hasText("Waiting for funds") and hasAnyAncestor(isDialog())).assertExists()
        compose
            .onNode(hasText("Add money") and hasAnyAncestor(isDialog()))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Amount (₦)").performTextInput("5000")
        compose.onNodeWithText("Review top-up").performScrollTo().performClick()
        compose.onNodeWithText("Add demo money").performScrollTo().performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("₦133,450.00").assertIsDisplayed()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("MTN airtime").performScrollTo().performClick()
        compose.onNodeWithText("Complete demo payment").performScrollTo().performClick()
        compose.onNodeWithText("All taken care of.").assertExists()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("₦3,450.00").assertIsDisplayed()
    }
}
