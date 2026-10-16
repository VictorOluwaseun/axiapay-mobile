package com.kesofty.axiapay

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.domain.wallet.*
import com.kesofty.axiapay.presentation.auth.AuthUiState
import com.kesofty.axiapay.ui.auth.AccountWalletCard
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import java.io.File
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Checks the real wallet card's recovery action and readable large-font content with synthetic
 * display data.
 */
class AccountWalletUiTest {
    @get:Rule val compose = createComposeRule()

    /**
     * Missing data remains explicit, and retry emits a single event rather than starting work
     * during rendering.
     */
    @Test
    fun missingWalletHasAnExplicitRetryWithoutAFabricatedBalance() {
        var retries = 0
        compose.setContent {
            AxiapayTheme {
                AccountWalletCard(
                    AuthUiState(wallet = WalletLookupResult.Failed(WalletIssue.Missing)),
                ) {
                    retries++
                }
            }
        }
        compose.onNodeWithTag("wallet-error").assertIsDisplayed()
        compose.onNodeWithTag("wallet-available").assertDoesNotExist()
        assertEquals(0, retries)
        compose.onNodeWithTag("wallet-refresh").performClick()
        assertEquals(1, retries)
    }

    /**
     * Long exact amounts and wallet IDs remain reachable at double font size in a scrolling compact
     * layout.
     */
    @Test
    fun walletContentRemainsReadableAtLargeFontSize() {
        val wallet =
            WalletSummary(
                "123e4567-e89b-42d3-a456-426614174001",
                "NGN",
                WalletStatus.Frozen,
                "9007199254740993".toBigInteger(),
                BigInteger.valueOf(100),
                "9007199254741093".toBigInteger(),
            )
        compose.setContent {
            AxiapayTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    ) {
                        AccountWalletCard(
                            AuthUiState(wallet = WalletLookupResult.Loaded(wallet)),
                        ) {}
                    }
                }
            }
        }
        compose.onNodeWithTag("wallet-available").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wallet-status").performScrollTo().assertTextEquals("Frozen")
        compose
            .onNodeWithTag("wallet-id")
            .performScrollTo()
            .assertTextContains(wallet.id, substring = true)
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "wallet-large-font.png",
            )
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("wallet-refresh").performScrollTo().assertIsDisplayed()
    }
}
