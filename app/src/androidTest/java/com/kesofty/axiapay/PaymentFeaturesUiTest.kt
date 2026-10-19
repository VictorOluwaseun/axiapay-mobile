package com.kesofty.axiapay

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.domain.config.PaymentFeatures
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Render controlled feature policy without real accounts, purchases or published config changes.
 */
class PaymentFeaturesUiTest {
    @get:Rule val compose = createComposeRule()

    /** Disabling new spending leaves history available while blocking another purchase. */
    @Test
    fun unavailableFeatureDisablesNewPurchaseFromHistory() {
        compose.setContent {
            AxiapayTheme {
                com.kesofty.axiapay.ui.purchases.PurchaseSheet(
                    com.kesofty.axiapay.presentation.purchases.PurchaseUiState(
                        enabled = true,
                        screen = com.kesofty.axiapay.presentation.purchases.PurchaseScreen.History,
                        featureAvailable = false,
                    ),
                    false,
                    false,
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("telecom-new").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("purchase-feature-disabled").performScrollTo().assertIsDisplayed()
    }

    /**
     * Disabled services remain readable and inaccessible in both palettes and at larger font sizes.
     */
    @Test
    fun featureAvailabilityUpdatesWithoutAutomaticNavigation() {
        val state =
            mutableStateOf(
                AxiaUiState(
                    DemoSessionSeed.create(LocalDate.now()),
                    NavigationState(tab = 1),
                    features = PaymentFeatures.AllEnabled.copy(airtime = false),
                ),
            )
        val offline = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val intents = mutableListOf<AxiaIntent>()
        compose.setContent {
            AxiapayTheme(offline = offline.value) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, scale.value),
                ) {
                    AxiaApp(state.value, { intents.add(it) })
                }
            }
        }
        for (isOffline in listOf(false, true)) {
            for (fontScale in listOf(1f, 1.5f)) {
                compose.runOnIdle {
                    offline.value = isOffline
                    scale.value = fontScale
                }
                compose
                    .onNode(hasText("Airtime", substring = false) and hasClickAction())
                    .performScrollTo()
                    .assertIsNotEnabled()
                compose
                    .onNode(hasText("Data", substring = false) and hasClickAction())
                    .performScrollTo()
                    .assertIsEnabled()
            }
        }
        compose.runOnIdle { state.value = state.value.copy(features = PaymentFeatures.AllEnabled) }
        assertTrue(intents.isEmpty())
        compose
            .onNode(hasText("Airtime", substring = false) and hasClickAction())
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(listOf(AxiaIntent.OpenService("Airtime")), intents) }
    }
}
