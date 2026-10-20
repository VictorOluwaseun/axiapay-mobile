package com.kesofty.axiapay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import com.kesofty.axiapay.domain.ConnectionMode
import com.kesofty.axiapay.presentation.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Installed Hilt owners keep draft identities, palette overrides, and Back behavior through routes.
 */
class NavigationFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var owner: AxiaViewModel

    /** Isolate all spending assertions to demo's unchanged simulated wallet. */
    @Before
    fun enterDemo() {
        Assume.assumeTrue(BuildConfig.FLAVOR == "demo")
        compose.awaitDemoWelcome()
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        compose.runOnIdle { owner = ViewModelProvider(compose.activity)[AxiaViewModel::class.java] }
        compose.waitUntil(10_000) { !owner.uiState.value.isRestoring }
        compose.runOnIdle {
            owner.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        }
        compose.waitUntil(5_000) {
            owner.uiState.value.session.connectionMode == ConnectionMode.ForceOffline
        }
    }

    /**
     * A dismissed sheet retains its draft and tab; recreation never changes money or request IDs.
     */
    @Test
    fun tabsSheetsAndDraftsRetainTheirOwnerAcrossRecreation() {
        compose.onNodeWithTag("dashboard-tab-1").performClick()
        compose.onNodeWithTag("navigation-payments").assertIsDisplayed()
        compose.runOnIdle { owner.accept(AxiaIntent.OpenService("Airtime")) }
        compose.onNodeWithText("Phone number").performScrollTo().performTextInput("08000000000")
        compose.onNodeWithText("Amount (₦)").performScrollTo().performTextInput("1000")
        val before = owner.uiState.value
        val form = (before.navigation.sheet as Sheet.Payment).form
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("08000000000").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(owner, ViewModelProvider(compose.activity)[AxiaViewModel::class.java])
            assertEquals(
                form.requestId,
                (owner.uiState.value.navigation.sheet as Sheet.Payment).form.requestId,
            )
            assertEquals(before.session.balance, owner.uiState.value.session.balance)
            assertEquals(before.session.payments, owner.uiState.value.session.payments)
            assertEquals(ConnectionMode.ForceOffline, owner.uiState.value.session.connectionMode)
        }
        Espresso.pressBack()
        compose.onNodeWithTag("navigation-payments").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-tab-1").assertIsSelected()
        compose.runOnIdle { assertEquals(form, owner.uiState.value.navigation.draft) }
        Espresso.pressBack()
        compose.onNodeWithTag("navigation-home").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-tab-0").assertIsSelected()
        compose.runOnIdle {
            assertEquals(form.requestId, owner.uiState.value.navigation.draft?.requestId)
            assertEquals(before.session.balance, owner.uiState.value.session.balance)
        }
    }

    /**
     * System Back follows import/recipients/services/tab history while retaining the same draft.
     */
    @Test
    fun bulkBackAndRecreationKeepDraftsWithoutSubmitting() {
        compose.onNodeWithTag("dashboard-tab-1").performClick()
        compose.runOnIdle {
            owner.accept(AxiaIntent.Bulk(BulkIntent.Open))
            owner.accept(
                AxiaIntent.Bulk(
                    BulkIntent.ChooseService(com.kesofty.axiapay.domain.BulkService.Airtime),
                ),
            )
        }
        compose.onNodeWithTag("navigation-bulk/recipients").assertIsDisplayed()
        val before = owner.uiState.value
        val draft = before.navigation.bulk.draft
        compose.runOnIdle { owner.accept(AxiaIntent.Bulk(BulkIntent.OpenImport)) }
        compose.onNodeWithTag("navigation-bulk/import").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("navigation-bulk/recipients").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("navigation-bulk/recipients").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(draft, owner.uiState.value.navigation.bulk.draft)
            assertEquals(before.session.balance, owner.uiState.value.session.balance)
            assertEquals(before.session.batches, owner.uiState.value.session.batches)
        }
        Espresso.pressBack()
        compose.onNodeWithTag("navigation-bulk/services").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("navigation-payments").assertIsDisplayed()
        compose.runOnIdle { assertEquals(draft?.id, owner.uiState.value.navigation.bulk.draft?.id) }
    }

    /** A restored sent SMS stays uncertain until explicit reconciliation, with one payment ID. */
    @Test
    fun smsNavigationRestoresUnknownOutcomeWithoutResubmitting() {
        compose.runOnIdle { owner.accept(AxiaIntent.OpenService("Airtime")) }
        compose.onNodeWithText("Phone number").performScrollTo().performTextInput("08000000000")
        compose.onNodeWithText("Amount (₦)").performScrollTo().performTextInput("1000")
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        compose.onNodeWithText("Review SMS request").performScrollTo().performClick()
        compose.onNodeWithText("Send demo SMS").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("Sent. Waiting for a reply.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Sent. Waiting for a reply.").performScrollTo().assertIsDisplayed()
        val sent = owner.uiState.value.session
        val request = sent.smsOutbox.single()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Sent. Waiting for a reply.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(sent.balance, owner.uiState.value.session.balance)
            assertEquals(sent.payments, owner.uiState.value.session.payments)
            assertEquals(request, owner.uiState.value.session.smsOutbox.single())
            owner.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOnline))
        }
        compose.waitUntil(5_000) {
            owner.uiState.value.session.connectionMode == ConnectionMode.ForceOnline
        }
        compose.onNodeWithText("Check demo status").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            owner.uiState.value.session.payments.any {
                it.id == request.id &&
                    it.status == com.kesofty.axiapay.domain.PaymentStatus.Successful
            }
        }
        compose.runOnIdle {
            assertEquals(Sheet.Sms(request.id), owner.uiState.value.navigation.sheet)
        }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-home").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, owner.uiState.value.session.payments.count { it.id == request.id })
            assertEquals(sent.balance - 100_000, owner.uiState.value.session.balance)
        }
    }
}
