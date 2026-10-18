package com.kesofty.axiapay

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.domain.wallet.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.money
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import java.io.File
import java.math.BigInteger
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Test real Home rendering against a deliberately different simulated balance, including explicit
 * recovery and demo isolation.
 */
class HomeWalletUiTest {
    @get:Rule val compose = createComposeRule()
    private val demo = DemoSessionSeed.create(LocalDate.of(2026, 10, 16))
    private val wallet =
        WalletSummary(
            "123e4567-e89b-42d3-a456-426614174001",
            "NGN",
            WalletStatus.Active,
            BigInteger.valueOf(12500),
            BigInteger.valueOf(2500),
            BigInteger.valueOf(15000),
        )

    /**
     * Render the production dashboard layout with account-owned wallet input and isolated preview
     * state.
     */
    private fun show(
        state: HomeWalletState,
        onRefresh: () -> Unit = {},
        onAccount: () -> Unit = {},
        offline: Boolean = false,
        largeFont: Boolean = false,
        onService: (String) -> Unit = {},
    ) {
        compose.setContent {
            var uiState by remember {
                mutableStateOf(AxiaUiState(demo.copy(offline = offline), NavigationState()))
            }
            AxiapayTheme(offline = offline) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides
                        Density(density.density, if (largeFont) 2f else density.fontScale),
                ) {
                    AxiaApp(
                        uiState,
                        { intent ->
                            when (intent) {
                                is AxiaIntent.HideBalance ->
                                    uiState =
                                        uiState.copy(
                                            session = uiState.session.copy(hidden = intent.hidden),
                                        )
                                is AxiaIntent.OpenService -> {
                                    onService(intent.route)
                                    when (intent.route) {
                                        "profile" ->
                                            uiState =
                                                uiState.copy(
                                                    navigation =
                                                        NavigationState(sheet = Sheet.Profile),
                                                )
                                        else -> Unit
                                    }
                                }
                                AxiaIntent.CloseSheet ->
                                    uiState = uiState.copy(navigation = NavigationState())
                                else -> Unit
                            }
                        },
                        homeWallet = state,
                        authShortcutLabel = "Account & sign out",
                        onRefreshWallet = onRefresh,
                        onOpenAccount = onAccount,
                    )
                }
            }
        }
    }

    /**
     * Available server funds replace the sample amount; hiding changes presentation without
     * changing either wallet.
     */
    @Test
    fun realBalanceAndVisibilityIgnoreDemoFunds() {
        val routes = mutableListOf<String>()
        show(HomeWalletState.Loaded(wallet), onService = { routes += it })
        saveScreenshot("dashboard-wallet-api.png")
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦125.00")
        compose.onNodeWithText(money(demo.balance, true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide balance").performClick()
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦ ••••••")
        compose.onNodeWithContentDescription("Show balance").performClick()
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦125.00")
        compose.onNodeWithTag("home-wallet-add-money").performClick()
        compose.onNodeWithTag("home-wallet-send-money").performClick()
        assertEquals(listOf("Add money", "Send money"), routes)
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦125.00")
        compose.onNodeWithTag("home-wallet-refresh").assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-account").assertDoesNotExist()
    }

    /** Account details remain accessible from Profile after replacing the card's account button. */
    @Test
    fun profileAccountEntryClosesTheSheetBeforeOpeningAccountDetails() {
        var opened = 0
        show(HomeWalletState.Loaded(wallet), onAccount = { opened++ })
        compose.onNodeWithContentDescription("Open profile").performClick()
        compose.onNodeWithText("Account & sign out").performScrollTo().performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("A space for you.").assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-add-money").assertIsDisplayed()
    }

    /** Capture isolated fixture UI for visual comparison without accessing the phone's session. */
    private fun saveScreenshot(name: String) {
        val image = compose.onRoot().captureToImage()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Missing wallets display a recoverable failure and never substitute sample money or zero. */
    @Test
    fun failedWalletHasExplicitRetryWithoutADemoAmount() {
        var retries = 0
        show(HomeWalletState.Failed(WalletIssue.Missing), { retries++ })
        compose.onNodeWithTag("home-wallet-error").assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-balance").assertDoesNotExist()
        compose.onNodeWithText(money(demo.balance, true)).assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-add-money").assertIsNotEnabled()
        compose.onNodeWithTag("home-wallet-send-money").assertIsNotEnabled()
        compose.onNodeWithTag("home-wallet-refresh").performClick()
        assertEquals(1, retries)
    }

    /**
     * Loading keeps wallet actions disabled and exposes neither previous real amounts nor sample
     * funds.
     */
    @Test
    fun loadingIsExplicitAndWalletActionsAreDisabled() {
        show(HomeWalletState.Loading)
        compose.onNodeWithTag("home-wallet-loading").assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-balance").assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-add-money").assertIsNotEnabled()
        compose.onNodeWithTag("home-wallet-send-money").assertIsNotEnabled()
        compose.onNodeWithText(money(demo.balance, true)).assertDoesNotExist()
    }

    /**
     * Signed-out server dashboards offer account entry; accessing a preview cannot grant sample
     * funds to a real wallet.
     */
    @Test
    fun signedOutDashboardPromptsForSignIn() {
        var opened = 0
        show(HomeWalletState.SignedOut, onAccount = { opened++ })
        compose.onNodeWithTag("home-wallet-signed-out").assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-balance").assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-refresh").assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-add-money").assertIsNotEnabled()
        compose.onNodeWithTag("home-wallet-send-money").assertIsNotEnabled()
        compose.onNodeWithTag("home-wallet-account").performClick()
        assertEquals(1, opened)
    }

    /** Demo composition preserves its existing balance badge and simulated wallet actions. */
    @Test
    fun demoDashboardKeepsItsOwnWallet() {
        show(HomeWalletState.Demo)
        compose.onNodeWithText(money(demo.balance, true)).assertIsDisplayed()
        compose.onNodeWithText("Demo").assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-add-money").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("home-wallet-send-money").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("home-wallet-balance").assertDoesNotExist()
        saveScreenshot("dashboard-wallet-demo.png")
    }

    /**
     * Offline colour conversion applies to the real wallet and amount text wraps at accessible font
     * sizes.
     */
    @Test
    fun realWalletSupportsOfflinePaletteAndLargeFonts() {
        show(HomeWalletState.Loaded(wallet), offline = true, largeFont = true)
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦125.00")
        compose.onNodeWithTag("home-wallet-add-money").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-send-money").assertIsDisplayed()
        val image = compose.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        for (row in 0 until pixels.height step 18) {
            for (column in 0 until pixels.width step 18) {
                val color = pixels[column, row]
                assertTrue(
                    abs(color.red - color.green) < .02f && abs(color.green - color.blue) < .02f,
                )
            }
        }
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "home-wallet-large-font.png",
            )
        file.outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
