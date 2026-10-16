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
import com.kesofty.axiapay.ui.walletMoney
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
                            if (intent is AxiaIntent.HideBalance) {
                                uiState =
                                    uiState.copy(
                                        session = uiState.session.copy(hidden = intent.hidden),
                                    )
                            }
                        },
                        homeWallet = state,
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
        var refreshed = 0
        var opened = 0
        show(HomeWalletState.Loaded(wallet), { refreshed++ }, { opened++ })
        compose
            .onNodeWithTag("home-wallet-balance")
            .assertTextEquals(walletMoney(wallet.availableMinorUnits, "NGN"))
        compose.onNodeWithText(money(demo.balance, true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide balance").performClick()
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦ ••••••")
        compose.onNodeWithContentDescription("Show balance").performClick()
        compose
            .onNodeWithTag("home-wallet-balance")
            .assertTextEquals(walletMoney(wallet.availableMinorUnits, "NGN"))
        assertEquals(0, refreshed)
        compose.onNodeWithTag("home-wallet-refresh").performClick()
        compose.onNodeWithTag("home-wallet-account").performClick()
        assertEquals(1, refreshed)
        assertEquals(1, opened)
    }

    /** Missing wallets display a recoverable failure and never substitute sample money or zero. */
    @Test
    fun failedWalletHasExplicitRetryWithoutADemoAmount() {
        var retries = 0
        show(HomeWalletState.Failed(WalletIssue.Missing), { retries++ })
        compose.onNodeWithTag("home-wallet-error").assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-balance").assertDoesNotExist()
        compose.onNodeWithText(money(demo.balance, true)).assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-refresh").performClick()
        assertEquals(1, retries)
    }

    /**
     * Loading prevents duplicate refresh and exposes neither previous real amounts nor sample
     * funds.
     */
    @Test
    fun loadingIsExplicitAndRefreshIsDisabled() {
        show(HomeWalletState.Loading)
        compose.onNodeWithTag("home-wallet-loading").assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-balance").assertDoesNotExist()
        compose.onNodeWithTag("home-wallet-refresh").assertIsNotEnabled()
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
        compose.onNodeWithTag("home-wallet-account").performClick()
        assertEquals(1, opened)
    }

    /** Demo composition preserves its existing balance badge and simulated wallet actions. */
    @Test
    fun demoDashboardKeepsItsOwnWallet() {
        show(HomeWalletState.Demo)
        compose.onNodeWithText(money(demo.balance, true)).assertIsDisplayed()
        compose.onNodeWithText("Demo").assertIsDisplayed()
        compose.onNodeWithText("Add money").assertIsDisplayed()
        compose.onNodeWithTag("home-wallet-balance").assertDoesNotExist()
    }

    /**
     * Offline colour conversion applies to the real wallet and amount text wraps at accessible font
     * sizes.
     */
    @Test
    fun realWalletSupportsOfflinePaletteAndLargeFonts() {
        show(HomeWalletState.Loaded(wallet), offline = true, largeFont = true)
        compose
            .onNodeWithTag("home-wallet-balance")
            .assertTextEquals(walletMoney(wallet.availableMinorUnits, "NGN"))
        compose.onNodeWithTag("home-wallet-refresh").performScrollTo().assertIsDisplayed()
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
