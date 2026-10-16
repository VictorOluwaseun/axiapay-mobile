package com.kesofty.axiapay

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OfflineUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val internet = DeviceInternet()

    /** Runs simulated wallet/network assertions only in the isolated demo flavor. */
    @Before
    fun prepareNetwork() {
        Assume.assumeTrue(
            "Simulated wallet flows require the demo flavor",
            BuildConfig.FLAVOR == "demo",
        )
        internet.capture()
        reconnect()
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
    }

    @After
    fun restoreNetwork() {
        internet.restore()
    }

    private fun reconnect() {
        internet.setOnline(true)
        compose.waitUntil(timeoutMillis = 30_000) { internet.isOnline() }
        compose.waitForIdle()
    }

    private fun offline() {
        internet.setOnline(false)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Offline mode").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Offline mode").assertIsDisplayed()
    }

    private fun smsReview() {
        compose.onNodeWithText("Airtime", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Phone number").performTextInput("08000000000")
        compose.onNodeWithText("Amount (₦)").performTextInput("1000")
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("SIM 2").performScrollTo().performClick()
        compose.onNodeWithText("Review SMS request").performScrollTo().performClick()
    }

    @Test
    fun smsFlowRetainsUnknownOutcomeAcrossRecreationAndReconnection() {
        offline()
        compose.onNodeWithText("Cached balance").assertIsDisplayed()
        smsReview()
        compose.onNodeWithText("Ready to send by SMS?").assertExists()
        compose.onNodeWithText("Send demo SMS").performScrollTo().performClick()
        compose.onNodeWithText("Sent. Waiting for a reply.").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Sent. Waiting for a reply.").assertExists()
        reconnect()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Check demo status").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Check demo status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check demo status").performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("₦127,450.00").assertIsDisplayed()
        compose.onNodeWithText("Available balance").assertIsDisplayed()
    }

    @Test
    fun greyscaleIncludesHomeNavigationAndThePaymentSheetAndRetryIsExplicit() {
        offline()
        assertGreyscale(compose.onRoot().captureToImage().toPixelMap())
        smsReview()
        assertGreyscale(compose.onNode(isDialog()).captureToImage().toPixelMap())
        compose.onNodeWithText("Try a failed-send scenario").performScrollTo().performClick()
        compose.onNodeWithText("Let's try that again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry same SMS request").performScrollTo().performClick()
        compose.onNodeWithText("Sent. Waiting for a reply.").performScrollTo().assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("Awaiting confirmation").assertIsDisplayed()
    }

    @Test
    fun partialFormAndSavedDraftSurviveActualNetworkChanges() {
        compose.onNodeWithText("Airtime", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Phone number").performTextInput("080123")
        compose.onNodeWithText("Amount (₦)").performTextInput("2000")
        internet.setOnline(false)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Offline · Pay by SMS").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Phone number").assertTextContains("080123")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Amount (₦)").assertTextContains("2000")
        compose.onNodeWithText("Save draft for later").performScrollTo().performClick()
        reconnect()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("You're online").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Continue airtime draft").performScrollTo().performClick()
        compose.onNodeWithText("Phone number").assertTextContains("080123")
        compose.onNodeWithText("Amount (₦)").assertTextContains("2000")
        Espresso.pressBack()
        compose.onNodeWithText("Available balance").assertIsDisplayed()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
        assertHasColour(compose.onRoot().captureToImage().toPixelMap())
    }

    private fun assertHasColour(pixels: androidx.compose.ui.graphics.PixelMap) {
        val coloured =
            (0 until pixels.height step 12).any { y ->
                (0 until pixels.width step 12).any { x ->
                    val color = pixels[x, y]
                    abs(color.red - color.green) > .05f
                }
            }
        assertTrue("The online palette should return automatically", coloured)
    }

    private fun assertGreyscale(pixels: androidx.compose.ui.graphics.PixelMap) {
        for (y in 0 until pixels.height step 12) {
            for (x in 0 until pixels.width step 12) {
                val color = pixels[x, y]
                assertTrue(
                    "Non-grey UI pixel at $x,$y: $color",
                    abs(color.red - color.green) < .015f && abs(color.green - color.blue) < .015f,
                )
            }
        }
    }
}
