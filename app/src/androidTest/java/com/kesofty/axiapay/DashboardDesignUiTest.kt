package com.kesofty.axiapay

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.domain.wallet.WalletStatus
import com.kesofty.axiapay.domain.wallet.WalletSummary
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import java.io.File
import java.math.BigInteger
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compares all dashboard sections using isolated state without accessing a customer's session. */
class DashboardDesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val session = DemoSessionSeed.create(LocalDate.of(2026, 10, 16))
    private val wallet =
        WalletSummary(
            "123e4567-e89b-42d3-a456-426614174001",
            "NGN",
            WalletStatus.Active,
            BigInteger.valueOf(12500),
            BigInteger.ZERO,
            BigInteger.valueOf(12500),
        )
    private val source = mutableStateOf<HomeWalletState>(HomeWalletState.Demo)
    private val state =
        mutableStateOf(
            AxiaUiState(session, NavigationState()),
        )
    private val intents = mutableListOf<AxiaIntent>()
    private val routes =
        listOf(
            "Airtime",
            "Data",
            "Electricity",
            "TV",
            "Education",
            "Insurance",
            "Convert airtime",
            "More",
        )

    /** Mirrors state reduction for navigation only; fixture actions never invoke payment work. */
    private fun show(fontScale: Float = 1f, compact: Boolean = false, offline: Boolean = false) {
        compose.setContent {
            AxiapayTheme(offline = offline) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    Box(if (compact) Modifier.width(320.dp).fillMaxHeight() else Modifier) {
                        AxiaApp(
                            state.value,
                            { intent ->
                                intents += intent
                                when (intent) {
                                    is AxiaIntent.SelectTab ->
                                        state.value =
                                            state.value.copy(
                                                navigation = NavigationState(tab = intent.tab),
                                            )
                                    else -> Unit
                                }
                            },
                            homeWallet = source.value,
                        )
                    }
                }
            }
        }
    }

    /** Snapshots shortcut bounds independently from wallet text, values and account identity. */
    private fun shortcutBounds(): List<Rect> = routes.map {
        compose.onNodeWithTag("dashboard-service-$it").fetchSemanticsNode().boundsInRoot
    }

    /** Stores fixture screenshots for visual inspection without showing personal account data. */
    private fun screenshot(name: String): Bitmap {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** Releases native image buffers immediately when a capture is only for visual inspection. */
    private fun saveScreenshot(name: String) {
        screenshot(name).recycle()
    }

    /** Compares every pixel and releases both images before subsequent device checks run. */
    private fun assertSameScreenshots(message: String, first: Bitmap, second: Bitmap) {
        try {
            assertTrue(message, first.sameAs(second))
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    /** Real and demo Home share section placement and identical preview cards, rows and footer. */
    @Test
    fun homeMatchesDemoFromHeaderThroughRecentActivity() {
        show()
        val demoBounds = shortcutBounds()
        saveScreenshot("dashboard-demo-top.png")
        compose.runOnIdle { source.value = HomeWalletState.Loaded(wallet) }
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦125.00")
        assertEquals(demoBounds, shortcutBounds())
        saveScreenshot("dashboard-live-top.png")
        compose.onNodeWithTag("home-dashboard").performScrollToNode(hasText("  axiapay"))
        compose.onAllNodesWithText("Preview").assertCountEquals(2)
        val real = screenshot("dashboard-live-bottom.png")
        compose.runOnIdle { source.value = HomeWalletState.Demo }
        val demo = screenshot("dashboard-demo-bottom.png")
        assertSameScreenshots("Plan, activity and footer rendering must match", real, demo)
    }

    /** All three other tabs use the same layout regardless of the wallet source. */
    @Test
    fun paymentsActivityAndPlansMatchDemo() {
        show()
        for (tab in 1..3) {
            compose.runOnIdle {
                state.value = state.value.copy(navigation = NavigationState(tab = tab))
            }
            compose.onNodeWithTag("dashboard-tab-$tab").assertIsSelected()
            val demo = screenshot("dashboard-tab-$tab-demo.png")
            compose.runOnIdle { source.value = HomeWalletState.Loaded(wallet) }
            val real = screenshot("dashboard-tab-$tab-live.png")
            assertSameScreenshots("Tab $tab must retain demo rendering", demo, real)
            compose.runOnIdle { source.value = HomeWalletState.Demo }
        }
        assertTrue("Changing a render source must not dispatch navigation", intents.isEmpty())
    }

    /**
     * Compact screens and 200 percent text retain complete tab labels and distinct touch targets.
     */
    @Test
    fun compactLargeTextKeepsNavigationReadableAcrossEveryTab() {
        source.value = HomeWalletState.Loaded(wallet)
        show(fontScale = 2f, compact = true)
        for (tab in 0..3) {
            val target = compose.onNodeWithTag("dashboard-tab-$tab")
            target.assertIsDisplayed().performClick().assertIsSelected()
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithTag("dashboard-tab-label-$tab", useUnmergedTree = true)
                .performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
                ) {
                    it(layouts)
                }
            assertEquals(1, layouts.size)
            saveScreenshot("dashboard-large-tab-$tab.png")
            assertFalse(
                "Tab $tab must not clip its label: size=${layouts.single().size}, height=${layouts.single().multiParagraph.height}, width=${layouts.single().multiParagraph.width}",
                layouts.single().hasVisualOverflow,
            )
        }
        compose.onNodeWithTag("dashboard-tab-0").performClick()
        compose.onNodeWithTag("home-dashboard").performScrollToNode(hasText("  axiapay"))
        saveScreenshot("dashboard-large-home-bottom.png")
        val bounds =
            (0..3).map {
                compose.onNodeWithTag("dashboard-tab-$it").fetchSemanticsNode().boundsInRoot
            }
        bounds.forEachIndexed { index, rect ->
            bounds.drop(index + 1).forEach { other -> assertFalse(rect.overlaps(other)) }
        }
    }

    /** Offline greyscale keeps the shared dashboard layout and available shortcuts. */
    @Test
    fun offlineDashboardPreservesSharedSections() {
        source.value = HomeWalletState.Loaded(wallet)
        state.value = state.value.copy(session = session.copy(offline = true))
        show(offline = true)
        compose.onNodeWithTag("dashboard-service-Data").assertIsEnabled()
        compose.onNodeWithTag("dashboard-service-Airtime").assertIsEnabled()
        saveScreenshot("dashboard-offline.png")
    }
}
