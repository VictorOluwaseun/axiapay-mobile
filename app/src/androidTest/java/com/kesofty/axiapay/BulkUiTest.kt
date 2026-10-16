package com.kesofty.axiapay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Exercises quick entry, existing wallet recovery and Activity using the real flavor Hilt graph.
 */
class BulkUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Runs simulated wallet recovery in demo through the public welcome action. */
    @Before
    fun enterDemo() {
        Assume.assumeTrue(
            "Simulated wallet flows require the demo flavor",
            BuildConfig.FLAVOR == "demo",
        )
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
    }

    /** Opens the chosen service directly with no naming or separate recipient-editor screen. */
    private fun openService(service: String) {
        compose.onNodeWithText("Payments").performClick()
        compose.onNodeWithTag("bulk-entry").performClick()
        compose.onNodeWithTag("bulk-service-$service").performScrollTo().performClick()
    }

    /** Dismisses only a visible IME on the active root, including a Compose dialog window. */
    private fun dismissKeyboard() {
        if (!keyboardVisible()) return
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { !keyboardVisible() }
        compose.waitForIdle()
    }

    /** Reads the focused window instead of assuming the Activity owns a dialog's keyboard. */
    private fun keyboardVisible(): Boolean {
        var visible = false
        Espresso.onView(isRoot()).check { view, failure ->
            if (failure != null) throw failure
            visible =
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) ==
                    true
        }
        return visible
    }

    /** Brings an inline field into view without depending on screen size or keyboard visibility. */
    private fun field(tag: String): SemanticsNodeInteraction {
        dismissKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("bulk-recipients-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    /**
     * Same-service execution and failed-only retries still preserve exact wallet and history
     * totals.
     */
    @Test
    fun quickBatchRetriesOnlyFailedRecipientAndAppearsInActivity() {
        openService("Airtime")
        compose.onNodeWithText("Try demo list").performScrollTo().performClick()
        compose.onNodeWithText("Review & pay").performClick()
        compose.onNodeWithText("Pay ₦6,000.00 · Demo").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Review 1 failed payment").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("3 of 4 successful").assertExists()
        compose.onNodeWithText("Review 1 failed payment").performClick()
        compose.onNodeWithText("Retry ₦1,500.00 · Demo").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("4 of 4 successful").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("4 of 4 successful").assertExists()
        compose.onNodeWithText("View batch history").performClick()
        compose.onNodeWithText("Batches").assertIsSelected()
        compose.onNodeWithText("Bulk airtime").performScrollTo().performClick()
        compose.onNodeWithText("4 of 4 successful").assertExists()
        compose.onNodeWithContentDescription("Save and close bulk payments").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("₦122,450.00").assertIsDisplayed()
    }

    /** Plus retains provider/amount, accepts the last row on review and never asks for a name. */
    @Test
    fun plusAddsInlineAndReviewIncludesLastRecipient() {
        openService("Airtime")
        field("bulk-recipient-reference").performTextReplacement("08030000001")
        field("bulk-recipient-amount").performTextReplacement("1250.25")
        dismissKeyboard()
        compose.onNodeWithText("Add another recipient").performScrollTo().performClick()
        field("bulk-recipient-reference").performTextReplacement("08030000002")
        field("bulk-recipient-amount").assertTextContains("1250.25")
        dismissKeyboard()
        compose.onNodeWithText("Review & pay").performClick()
        compose.onNodeWithText("Pay ₦2,500.50 · Demo").assertIsEnabled()
        compose.onNodeWithTag("bulk-review-list").performScrollToNode(hasText("08030000002"))
        compose.onNodeWithText("08030000002").assertExists()
    }

    /** A normal Data form carries its existing details into the first row and reuses its plan. */
    @Test
    fun individualDataExpandsWithoutLosingFirstRecipient() {
        compose.onNodeWithText("Data", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Phone number").performTextInput("08030000001")
        dismissKeyboard()
        compose.onNodeWithText("Add another recipient").performScrollTo().performClick()
        compose.onNodeWithText("Bulk data").assertExists()
        field("bulk-recipient-reference").performTextReplacement("08030000002")
        dismissKeyboard()
        compose.onNodeWithText("8 GB").assertIsSelected()
        compose.onNodeWithText("Review & pay").performClick()
        compose.onNodeWithText("Pay ₦7,000.00 · Demo").assertIsEnabled()
    }

    /** Offline editor recreation and service switching retain input; reconnecting never spends. */
    @Test
    fun offlineDraftKeepsEditorAndReviewUntilReconnected() {
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose.onNodeWithText("Force offline").performScrollTo().performClick()
        Espresso.pressBack()
        openService("Airtime")
        field("bulk-recipient-reference").performTextReplacement("08030000001")
        field("bulk-recipient-amount").performTextReplacement("1500")
        dismissKeyboard()
        compose.activityRule.scenario.recreate()
        field("bulk-recipient-reference").assertTextContains("08030000001")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("bulk-service-TV").performScrollTo().performClick()
        field("bulk-recipient-reference").performTextReplacement("01234567890")
        dismissKeyboard()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("bulk-service-Airtime").performScrollTo().performClick()
        field("bulk-recipient-reference").assertTextContains("08030000001")
        compose.onNodeWithText("Review & pay").performClick()
        compose.onNodeWithText("Pay ₦1,500.00 · Demo").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Save and close bulk payments").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose.onNodeWithText("Force online").performScrollTo().performClick()
        Espresso.pressBack()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
        openService("Airtime")
        compose.onNodeWithText("Review & pay").performClick()
        compose.onNodeWithText("Pay ₦1,500.00 · Demo").assertIsEnabled()
    }

    /** Electricity preserves kobo through wallet funding and returns to the unsubmitted review. */
    @Test
    fun manualElectricityRetainsExactAmountAcrossFunding() {
        openService("Electricity")
        field("bulk-recipient-reference").performTextReplacement("01234567890")
        field("bulk-recipient-amount").performTextReplacement("135000.25")
        dismissKeyboard()
        compose.onNodeWithText("Review & pay").performClick()
        compose.onNodeWithText("Add demo money").performClick()
        compose.onNodeWithText("Amount (₦)").performTextInput("20000")
        compose.onNodeWithText("Review top-up").performScrollTo().performClick()
        compose
            .onNode(hasText("Add demo money") and hasAnyAncestor(isDialog()))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Pay ₦135,000.25 · Demo").assertIsEnabled()
        compose.onNodeWithContentDescription("Save and close bulk payments").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("₦148,450.00").assertIsDisplayed()
    }
}
