package com.kesofty.axiapay

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.purchases.PurchaseActivityState
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.purchases.PurchaseActivityScreen
import com.kesofty.axiapay.ui.purchases.PurchaseReceiptSheet
import com.kesofty.axiapay.ui.purchases.RecentPurchases
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import com.kesofty.axiapay.wallet.domain.*
import com.kesofty.axiapay.wallet.presentation.HomeWalletState
import com.kesofty.axiapay.wallet.ui.walletMoney
import java.io.File
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Real Home rendering uses isolated server observations, never the device's account or payment
 * APIs.
 */
class HomeRecentPurchasesUiTest {
    @get:Rule val compose = createComposeRule()
    private val demo = DemoSessionSeed.create(LocalDate.of(2026, 10, 16))
    private val owner = PurchaseOwner("account", "https://fixture.example/api/v1/")
    private val activity =
        mutableStateOf(PurchaseActivityState(accountId = owner.accountId, visible = true))
    private val dashboard = mutableStateOf(AxiaUiState(demo, NavigationState()))
    private val intents = mutableListOf<AxiaIntent>()
    private var refreshes = 0
    private var accountOpens = 0

    /**
     * Build exact observed purchase/credit values without invoking spending or calculating
     * commission.
     */
    private fun entry(
        index: Int,
        reward: PurchaseCashbackStatus = PurchaseCashbackStatus.Credited,
        feeMinorUnits: Long = 0,
    ): PurchaseProgress {
        val time = Instant.parse("2026-10-25T20:00:00Z").minusSeconds(index * 60L)
        val quote =
            PurchaseQuote(
                id = "quote-$index",
                walletId = "wallet",
                productId = "product-$index",
                displayName = "Purchased MTN plan $index",
                providerCode = "mtn",
                recipientIdentifier = "+2348012345678",
                currency = "NGN",
                principalMinorUnits = BigInteger.valueOf(10000),
                feeMinorUnits = BigInteger.valueOf(feeMinorUnits),
                totalDebitMinorUnits = BigInteger.valueOf(10000 + feeMinorUnits),
                createdAt = time,
                expiresAt = time.plusSeconds(300),
                isSubmissionAvailable = false,
                service = PurchaseService.Data,
            )
        val request =
            PurchaseRequest(
                owner,
                "request-$index",
                quote.id,
                time,
                acknowledged = true,
                service = PurchaseService.Data,
            )
        val payment =
            PurchasePayment(
                id = "payment-$index",
                requestKey = request.requestKey,
                status = PurchasePaymentStatus.Succeeded,
                quote = quote,
                createdAt = time,
                submissionStartedAt = time,
                cashback =
                    PurchaseCashback(
                        reward,
                        if (reward == PurchaseCashbackStatus.Credited) BigInteger.valueOf(300)
                        else null,
                    ),
            )
        return PurchaseProgress(request, payment, quote)
    }

    /**
     * Render production slots and navigation with a controlled account, layout and read-only
     * callbacks.
     */
    private fun show(largeFont: Boolean = false, offline: Boolean = false) {
        dashboard.value = dashboard.value.copy(session = demo.copy(offline = offline))
        compose.setContent {
            AxiapayTheme(offline = offline) {
                CompositionLocalProvider(
                    LocalDensity provides
                        Density(LocalDensity.current.density, if (largeFont) 2f else 1f),
                ) {
                    Box(if (largeFont) Modifier.width(320.dp).fillMaxHeight() else Modifier) {
                        AxiaApp(
                            dashboard.value,
                            onIntent = { intent ->
                                intents += intent
                                when (intent) {
                                    is AxiaIntent.SelectTab ->
                                        dashboard.value =
                                            dashboard.value.copy(
                                                navigation = NavigationState(tab = intent.tab),
                                            )
                                    is AxiaIntent.HideBalance ->
                                        dashboard.value =
                                            dashboard.value.copy(
                                                session =
                                                    dashboard.value.session.copy(
                                                        hidden = intent.hidden,
                                                    ),
                                            )
                                    else -> Unit
                                }
                            },
                            homeWallet =
                                HomeWalletState.Loaded(
                                    WalletSummary(
                                        "wallet",
                                        "NGN",
                                        WalletStatus.Active,
                                        BigInteger.valueOf(328741),
                                        BigInteger.ZERO,
                                        BigInteger.valueOf(328741),
                                    ),
                                ),
                            recentActivityContent = {
                                RecentPurchases(
                                    activity.value,
                                    dashboard.value.session.hidden,
                                    { refreshes++ },
                                    { accountOpens++ },
                                    {
                                        activity.value =
                                            activity.value.copy(selectedRequestKey = it)
                                    },
                                )
                            },
                            activityContent = {
                                PurchaseActivityScreen(
                                    activity.value,
                                    { refreshes++ },
                                    { accountOpens++ },
                                    {
                                        activity.value =
                                            activity.value.copy(selectedRequestKey = it)
                                    },
                                )
                            },
                        )
                        activity.value.selectedEntry?.let { entry ->
                            PurchaseReceiptSheet(
                                entry,
                                activity.value.loading,
                                activity.value.issue,
                                { refreshes++ },
                                { activity.value = activity.value.copy(selectedRequestKey = null) },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Home limits to three real purchases, displays actual cashback, and View all opens the shared
     * full list.
     */
    @Test
    fun realPurchasesReplacePreviewAndViewAllShowsFullActivity() {
        activity.value = activity.value.copy(entries = (0..3).map { entry(it) })
        show()
        compose
            .onNodeWithTag("home-dashboard")
            .performScrollToNode(hasTestTag("home-live-activity"))
        compose.onAllNodesWithTag("home-purchase-row").assertCountEquals(3)
        compose.onNodeWithText("Purchased MTN plan 3").assertDoesNotExist()
        compose.onNodeWithText("MTN data").assertDoesNotExist()
        compose
            .onAllNodes(
                hasText("Preview") and hasAnyAncestor(hasTestTag("home-recent-activity")),
                useUnmergedTree = true,
            )
            .assertCountEquals(0)
        compose
            .onAllNodesWithTag("purchase-cashback", useUnmergedTree = true)
            .onFirst()
            .assertTextContains("3.00", substring = true)
        compose
            .onAllNodesWithTag("home-purchase-amount", useUnmergedTree = true)
            .onFirst()
            .assertTextEquals(walletMoney(BigInteger.valueOf(10000), "NGN"))
        compose.onNodeWithText("View all").performScrollTo().performClick()
        compose
            .onNodeWithTag("live-activity")
            .assertIsDisplayed()
            .performScrollToNode(hasText("Purchased MTN plan 3"))
        compose.onNodeWithText("Purchased MTN plan 3").assertIsDisplayed()
        assertEquals(listOf(AxiaIntent.SelectTab(2)), intents)
        assertEquals(0, refreshes)
    }

    /**
     * Loading, empty and failure states are explicit; a failed refresh retains only confirmed real
     * rows.
     */
    @Test
    fun loadingEmptyAndFailedReadsNeverFallbackToPreview() {
        activity.value = activity.value.copy(loading = true)
        show()
        compose
            .onNodeWithTag("home-dashboard")
            .performScrollToNode(hasTestTag("home-live-activity"))
        compose.onNodeWithTag("home-activity-loading").assertIsDisplayed()
        compose.onNodeWithTag("home-activity-refresh").assertIsNotEnabled()
        compose.onNodeWithText("MTN data").assertDoesNotExist()
        compose.runOnIdle { activity.value = activity.value.copy(loading = false) }
        compose.onNodeWithText("Your purchases will appear here.").assertIsDisplayed()
        compose.runOnIdle {
            activity.value =
                activity.value.copy(entries = listOf(entry(0)), issue = PurchaseIssue.Unavailable)
        }
        compose
            .onNodeWithText(
                "Activity could not be refreshed. Try again to check the latest payment status.",
            )
            .assertIsDisplayed()
        compose.onNodeWithText("Purchased MTN plan 0").assertIsDisplayed()
        compose.onNodeWithTag("home-activity-refresh").performScrollTo().performClick()
        assertEquals(1, refreshes)
        assertTrue(intents.isEmpty())
        compose.runOnIdle { activity.value = PurchaseActivityState() }
        compose.onAllNodesWithTag("home-purchase-row").assertCountEquals(0)
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        assertEquals(1, accountOpens)
    }

    /** Compact double-font offline Home wraps rows and hides both payment and cashback amounts. */
    @Test
    fun compactOfflineRowsKeepCashbackReadableAndRespectHiddenAmounts() {
        activity.value = activity.value.copy(entries = listOf(entry(0)))
        show(largeFont = true, offline = true)
        compose
            .onNodeWithTag("home-dashboard")
            .performScrollToNode(hasTestTag("home-live-activity"))
        compose
            .onNodeWithTag("purchase-cashback", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("3.00", substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("purchase-cashback", useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) {
            it(layouts)
        }
        assertFalse(layouts.single().hasVisualOverflow)
        saveScreenshot("home-recent-purchases-large-offline.png")
        compose.runOnIdle {
            dashboard.value =
                dashboard.value.copy(session = dashboard.value.session.copy(hidden = true))
        }
        compose
            .onNodeWithTag("home-purchase-amount", useUnmergedTree = true)
            .assertTextEquals("••••")
        compose
            .onNodeWithTag("purchase-cashback", useUnmergedTree = true)
            .assertTextEquals("Cashback credited to wallet: ••••")
        compose.runOnIdle {
            activity.value =
                activity.value.copy(entries = listOf(entry(0, PurchaseCashbackStatus.Pending)))
        }
        compose
            .onNodeWithTag("purchase-cashback", useUnmergedTree = true)
            .assertTextEquals("Your purchase succeeded. Cashback is being confirmed.")
        assertTrue(intents.isEmpty())
    }

    /** Both row sources open their own exact receipt and return to the originating tab. */
    @Test
    fun homeAndActivityRowsOpenMatchingReadOnlyReceipts() {
        activity.value =
            activity.value.copy(entries = listOf(entry(0, feeMinorUnits = 50), entry(1)))
        show()
        compose
            .onNodeWithTag("home-dashboard")
            .performScrollToNode(hasTestTag("home-live-activity"))
        compose
            .onAllNodesWithTag("home-purchase-row")
            .onFirst()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag("purchase-receipt").assertIsDisplayed()
        assertReceiptValue("recipient", "+2348012345678")
        assertReceiptValue("amount", walletMoney(BigInteger.valueOf(10000), "NGN"))
        assertReceiptValue("fee", walletMoney(BigInteger.valueOf(50), "NGN"))
        assertReceiptValue("total", walletMoney(BigInteger.valueOf(10050), "NGN"))
        compose
            .onNode(
                hasTestTag("purchase-cashback") and hasAnyAncestor(hasTestTag("purchase-receipt")),
            )
            .performScrollTo()
            .assertTextContains("3.00", substring = true)
        assertReceiptValue("reference", "payment-0")
        compose.onNodeWithTag("purchase-receipt-refresh").performScrollTo().performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("purchase-receipt-done").performScrollTo().performClick()
        compose.onNodeWithTag("purchase-receipt").assertDoesNotExist()
        assertEquals(0, dashboard.value.navigation.tab)
        compose.onNodeWithText("View all").performScrollTo().performClick()
        compose.onNodeWithTag("live-activity").performScrollToNode(hasText("Purchased MTN plan 1"))
        compose.onNodeWithText("Purchased MTN plan 1").performClick()
        assertReceiptValue("reference", "payment-1")
        Espresso.pressBack()
        compose.onNodeWithTag("purchase-receipt").assertDoesNotExist()
        compose.onNodeWithTag("live-activity").assertIsDisplayed()
        assertEquals(2, dashboard.value.navigation.tab)
        assertEquals(listOf(AxiaIntent.SelectTab(2)), intents)
    }

    /**
     * Long references remain readable at double font size and unconfirmed data is never success.
     */
    @Test
    fun receiptSupportsLargeTextAndHonestIncompleteOutcomes() {
        val original = entry(0, PurchaseCashbackStatus.Pending)
        val reference = "973ded01-592e-477a-b9d8-ba9ffd1c7ad8"
        val observed = original.copy(payment = original.payment!!.copy(id = reference))
        activity.value =
            activity.value.copy(
                entries = listOf(observed),
                selectedRequestKey = observed.request.requestKey,
            )
        show(largeFont = true, offline = true)
        assertReceiptValue("reference", reference)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("purchase-receipt-reference").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) {
            it(layouts)
        }
        assertFalse(layouts.single().hasVisualOverflow)
        compose
            .onNode(
                hasTestTag("purchase-cashback") and hasAnyAncestor(hasTestTag("purchase-receipt")),
            )
            .performScrollTo()
            .assertTextEquals("Your purchase succeeded. Cashback is being confirmed.")
        compose.runOnIdle {
            activity.value =
                activity.value.copy(
                    entries = listOf(original.copy(payment = null, quote = null)),
                    issue = PurchaseIssue.Network,
                )
        }
        assertReceiptValue("amount", "Not available yet")
        assertReceiptValue("reference", "Not available yet")
        assertReceiptValue("request", original.request.requestKey)
        compose
            .onNodeWithText("Could not refresh this receipt. The last available details are shown.")
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNode(
                hasTestTag("live-activity-status") and
                    hasAnyAncestor(hasTestTag("purchase-receipt")),
            )
            .performScrollTo()
            .assertTextEquals("Not confirmed")
        assertTrue(intents.isEmpty())
        assertEquals(0, refreshes)
    }

    /** Scroll a receipt field into view and compare its full untruncated server value. */
    private fun assertReceiptValue(field: String, expected: String) {
        compose
            .onNodeWithTag("purchase-receipt-$field")
            .performScrollTo()
            .assertTextEquals(expected)
    }

    /**
     * Save only the synthetic fixture for visual verification, leaving device settings untouched.
     */
    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
