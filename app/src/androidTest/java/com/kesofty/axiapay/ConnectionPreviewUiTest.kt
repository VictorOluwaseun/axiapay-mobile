package com.kesofty.axiapay

import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the preview controls and palette without changing the device's connectivity. */
class ConnectionPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Enters the unchanged demo dashboard through the public welcome action. */
    @Before
    fun enterDemo() {
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
    }

    /** Both entry points share one persisted choice and leave demo funds unchanged. */
    @Test
    fun profileAndManageShareTheOverrideAndRestoreTheSelectedMode() {
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose.onNodeWithText("Automatic").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Force offline").performScrollTo().performClick()
        compose.onNodeWithText("Force offline").assertIsSelected()
        assertEquals(false, hasColour(compose.onNode(isDialog()).captureToImage().toPixelMap()))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Force offline").performScrollTo().assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithText("Offline preview").assertIsDisplayed()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
        compose.onNodeWithText("Manage").performClick()
        compose.onNodeWithText("Force online").performScrollTo().performClick()
        compose.onNodeWithText("Force online").assertIsSelected()
        assertEquals(true, hasColour(compose.onNode(isDialog()).captureToImage().toPixelMap()))
        Espresso.pressBack()
        compose.onNodeWithText("Online preview").assertIsDisplayed()
        compose.onNodeWithText("Available balance").assertIsDisplayed()
        compose.onNodeWithText("Manage").performClick()
        compose.onNodeWithText("Automatic").performScrollTo().performClick()
        compose.onNodeWithText("Automatic").assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithText("Manual override • Demo only").assertDoesNotExist()
        compose.onNodeWithText("₦128,450.00").assertIsDisplayed()
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
