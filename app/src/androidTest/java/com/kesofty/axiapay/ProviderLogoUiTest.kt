package com.kesofty.axiapay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Checks that logo controls preserve real form identity, restoration and bulk-provider defaults.
 */
class ProviderLogoUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Enters the public demo without replacing its Hilt graph or navigation state. */
    @Before
    fun enterDemo() {
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
    }

    /** Dismisses the focused sheet's keyboard and confirms its insets have closed. */
    private fun dismissKeyboard() {
        compose.waitForIdle()
        if (!isKeyboardVisible()) return
        // The sheet owns a dialog window, so the Activity's insets controller cannot hide its IME.
        Espresso.pressBack()
        compose.waitUntil(5_000) { !isKeyboardVisible() }
        compose.waitForIdle()
    }

    /** Reads keyboard visibility on the UI thread without keeping a window reference. */
    private fun isKeyboardVisible(): Boolean {
        var visible = false
        compose.runOnUiThread {
            visible =
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    /** Selects through the public picker instead of mutating a form or its ViewModel directly. */
    private fun chooseElectricityProvider(query: String, provider: String) {
        compose.onNodeWithTag("electricity-provider-selector").performClick()
        compose.onNodeWithTag("electricity-provider-search").performTextInput(query)
        compose.onNodeWithTag("electricity-provider-$provider").performClick()
        waitForPickerDismissal()
        compose.onNodeWithTag("electricity-provider-selector").assertTextContains(provider)
    }

    /** Waits for the modal's actual removal after its dismissal animation. */
    private fun waitForPickerDismissal() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("electricity-provider-picker").fetchSemanticsNodes().isEmpty()
        }
    }

    /** A logo selection survives recreation and reaches review as the original provider name. */
    @Test
    fun individualLogoSelectionRetainsProviderAndDraft() {
        compose.onNodeWithText("Airtime", useUnmergedTree = true).performClick()
        compose
            .onNodeWithContentDescription("Airtel")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithContentDescription("MTN").assertIsNotSelected()
        compose.onNodeWithText("Phone number").performTextInput("08030000001")
        compose.onNodeWithText("Amount (₦)").performScrollTo().performTextInput("1250.25")
        dismissKeyboard()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Airtel").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Phone number").assertTextContains("08030000001")
        compose.onNodeWithText("Amount (₦)").performScrollTo().assertTextContains("1250.25")
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("Airtel Airtime").assertIsDisplayed()
    }

    /** Provider and Postpaid choices remain independent and survive recreation and review edits. */
    @Test
    fun electricityProviderAndMeterSurviveRecreation() {
        compose
            .onNode(hasTextExactly("Electricity") and hasClickAction())
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("meter-type-Prepaid").assertIsSelected()
        compose.onNodeWithTag("meter-type-Postpaid").performClick().assertIsSelected()
        chooseElectricityProvider("YEDC", "Yola Electric")
        compose.onNodeWithTag("meter-type-Postpaid").assertIsSelected()
        compose.onNodeWithText("Meter number").performScrollTo().performTextInput("01234567890")
        compose.onNodeWithText("Amount (₦)").performScrollTo().performTextInput("4500")
        dismissKeyboard()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("electricity-provider-selector").assertTextContains("Yola Electric")
        compose.onNodeWithText("Meter number").performScrollTo().assertTextContains("01234567890")
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("Yola Electric Electricity").assertIsDisplayed()
        compose.onNodeWithText("Meter type").assertIsDisplayed()
        compose.onNodeWithText("Postpaid").assertIsDisplayed()
        compose.onNodeWithText("Edit details").performScrollTo().performClick()
        compose.onNodeWithTag("meter-type-Postpaid").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("meter-type-Prepaid").assertIsNotSelected()
    }

    /** Filtering, clearing and dismissal preserve the existing draft without selecting a result. */
    @Test
    fun searchAndDismissalPreserveDraftAndRestoreTheOpenPicker() {
        compose
            .onNode(hasTextExactly("Electricity") and hasClickAction())
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Meter number").performScrollTo().performTextInput("01234567890")
        compose.onNodeWithText("Amount (₦)").performScrollTo().performTextInput("4500")
        dismissKeyboard()
        compose.onNodeWithTag("electricity-provider-selector").performClick()
        compose.onNodeWithTag("electricity-provider-search").performTextInput("EKEDC")
        compose
            .onNodeWithTag("electricity-provider-Eko Electric")
            .assertIsDisplayed()
            .assertIsNotSelected()
        compose.onNodeWithTag("electricity-provider-Yola Electric").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("electricity-provider-search").assertTextContains("EKEDC")
        compose.onNodeWithTag("electricity-provider-Eko Electric").assertIsDisplayed()
        compose
            .onNodeWithTag("electricity-provider-search")
            .performTextReplacement("Unlisted supplier")
        compose.onNodeWithText("No providers found").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear provider search").performClick()
        compose.onNodeWithTag("electricity-provider-Ikeja Electric").assertIsSelected()
        compose.onNodeWithContentDescription("Close provider list").performClick()
        waitForPickerDismissal()
        compose.onNodeWithTag("electricity-provider-selector").assertTextContains("Ikeja Electric")
        compose.onNodeWithText("Meter number").performScrollTo().assertTextContains("01234567890")
        compose.onNodeWithText("Amount (₦)").performScrollTo().assertTextContains("4500")
    }

    /**
     * Adding a recipient retains a selected logo's provider through the existing bulk intent flow.
     */
    @Test
    fun bulkLogoSelectionBecomesTheNextRecipientDefault() {
        compose.onNodeWithText("Payments").performClick()
        compose.onNodeWithTag("bulk-entry").performClick()
        compose.onNodeWithTag("bulk-service-Electricity").performScrollTo().performClick()
        compose
            .onNodeWithTag("bulk-recipients-list")
            .performScrollToNode(hasTestTag("electricity-provider-selector"))
        chooseElectricityProvider("YEDC", "Yola Electric")
        compose
            .onNodeWithTag("bulk-recipients-list")
            .performScrollToNode(hasTestTag("bulk-recipient-reference"))
        compose.onNodeWithTag("bulk-recipient-reference").performTextInput("01234567890")
        dismissKeyboard()
        compose.onNodeWithText("Add another recipient").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose
            .onNodeWithTag("bulk-recipients-list")
            .performScrollToNode(hasTestTag("electricity-provider-selector"))
        compose
            .onNodeWithTag("electricity-provider-selector")
            .assertTextContains("Yola Electric")
            .performClick()
        compose.onNodeWithTag("electricity-provider-Ikeja Electric").assertIsNotSelected()
        compose
            .onNodeWithTag("electricity-provider-list")
            .performScrollToNode(hasTestTag("electricity-provider-Yola Electric"))
        compose.onNodeWithTag("electricity-provider-Yola Electric").assertIsSelected()
    }

    /** Additional logos retain draft identity while the international entry cannot be selected. */
    @Test
    fun addedAirtimeProviderRestoresAndInternationalRemainsDisabled() {
        compose.onNodeWithText("Airtime", useUnmergedTree = true).performClick()
        compose
            .onNodeWithContentDescription("T2 (9mobile)")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithContentDescription("Smile").assertIsNotSelected()
        compose
            .onNodeWithTag("provider-international-airtime")
            .performScrollTo()
            .assertIsNotEnabled()
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("T2 (9mobile)").assertIsSelected()
        compose.onNodeWithText("Phone number").performScrollTo().performTextInput("08030000001")
        compose.onNodeWithText("Amount (₦)").performScrollTo().performTextInput("1250.25")
        dismissKeyboard()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("T2 (9mobile)").performScrollTo().assertIsSelected()
        compose
            .onNodeWithTag("provider-international-airtime")
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("T2 (9mobile) Airtime").assertIsDisplayed()
    }

    /** Data uses the same new artwork and carries Smile into the next bulk recipient. */
    @Test
    fun smileDataBecomesTheNextBulkRecipientDefault() {
        compose.onNodeWithText("Data", useUnmergedTree = true).performClick()
        compose
            .onNodeWithContentDescription("Smile")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("provider-international-airtime").assertDoesNotExist()
        compose.onNodeWithText("Phone number").performScrollTo().performTextInput("08030000001")
        dismissKeyboard()
        compose.onNodeWithText("Add another recipient").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose
            .onNodeWithTag("bulk-recipients-list")
            .performScrollToNode(hasTestTag("provider-Smile"))
        compose.onNodeWithContentDescription("Smile").assertIsSelected()
        compose.onNodeWithContentDescription("T2 (9mobile)").assertIsNotSelected()
        compose.onNodeWithTag("provider-international-airtime").assertDoesNotExist()
    }
}
