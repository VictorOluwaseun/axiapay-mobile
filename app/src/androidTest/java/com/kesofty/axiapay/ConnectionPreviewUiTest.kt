package com.kesofty.axiapay

import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the preview controls and palette without changing the device's connectivity. */
class ConnectionPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Runs simulated wallet assertions in demo through the public welcome action. */
    @Before
    fun enterDemo() {
        Assume.assumeTrue(
            "Simulated wallet flows require the demo flavor",
            BuildConfig.FLAVOR == "demo",
        )
        compose.awaitDemoWelcome()
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
    }

    /** Both entry points share one persisted choice and leave demo funds unchanged. */
    @Test
    fun profileAndManageShareTheOverrideAndRestoreTheSelectedMode() {
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose.onNodeWithText("Automatic").performScrollTo().assertIsSelected()
        selectModeAndAwaitSelection("Force offline")
        assertEquals(false, hasColour(compose.onNode(isDialog()).captureToImage().toPixelMap()))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Force offline").performScrollTo().assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithText("Offline preview").assertIsDisplayed()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
        compose.onNodeWithText("Manage").performClick()
        selectModeAndAwaitSelection("Force online")
        assertEquals(true, hasColour(compose.onNode(isDialog()).captureToImage().toPixelMap()))
        Espresso.pressBack()
        compose.onNodeWithText("Online preview").assertIsDisplayed()
        compose.onNodeWithText("Available balance").assertIsDisplayed()
        compose.onNodeWithText("Manage").performClick()
        selectModeAndAwaitSelection("Automatic")
        Espresso.pressBack()
        compose.onNodeWithText("Manual override • Demo only").assertDoesNotExist()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
    }

    /** Await the committed preference's UI projection before checking selection or palette. */
    private fun selectModeAndAwaitSelection(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText(label).assertIsSelected()
    }

    /** Samples app pixels to check the actual rendered palette, including sheet controls. */
    private fun hasColour(pixels: PixelMap): Boolean =
        (0 until pixels.height step 12).any { y ->
            (0 until pixels.width step 12).any { x ->
                val color = pixels[x, y]
                abs(color.red - color.green) > .05f
            }
        }
}
