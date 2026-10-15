package com.kesofty.axiapay

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Full-capacity device flow uses real content IO, Hilt, Compose and private checkpoint storage. */
class BulkScaleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Covers distant rows, search, exact edits, recreation, offline pause and failed-only retry.
     */
    @Test
    fun thousandRecipientsRemainManageableThroughPaymentRecovery() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri =
            requireNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "axiapay-scale-device.csv")
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    },
                ),
            )
        try {
            resolver.openOutputStream(uri)!!.bufferedWriter().use { writer ->
                writer.appendLine("name,recipient,provider,amount")
                for (index in 1..1000) writer.appendLine(
                    "Demo recipient $index,0805${index.toString().padStart(7, '0')},MTN,100",
                )
            }
            compose.onNodeWithText("Explore demo").performScrollTo().performClick()
            waitForState { !it.isRestoring }
            compose.onNodeWithText("Payments").performClick()
            compose.onNodeWithTag("bulk-entry").performClick()
            compose.onNodeWithTag("bulk-service-Airtime").performClick()
            compose.onNodeWithTag("bulk-import").performClick()
            compose.runOnUiThread {
                val vm = owner()
                vm.accept(
                    AxiaIntent.Bulk(
                        BulkIntent.ImportFile(
                            vm.uiState.value.navigation.bulk.importState!!.id,
                            uri.toString(),
                        ),
                    ),
                )
            }
            waitForState { it.navigation.bulk.importState?.selectedIds?.size == 1000 }
            compose.onNodeWithText("Add selected (1,000)").assertIsEnabled()
            compose.onNodeWithTag("bulk-import-list").performScrollToIndex(1002)
            compose.onNodeWithTag("import-row-1001").assertExists()
            compose.onNodeWithText("Add selected (1,000)").performClick()
            compose.onNodeWithText("1,000 recipients").assertExists()
            compose.onNodeWithTag("bulk-recipients-list").performScrollToIndex(999)
            compose.onNodeWithTag("bulk-row-1000").assertIsDisplayed()
            // A lazy viewport must not compose all 1,000 recipients at once.
            val rendered =
                compose
                    .onAllNodes(
                        SemanticsMatcher("recipient rows") { node ->
                            node.config
                                .getOrElse(
                                    androidx.compose.ui.semantics.SemanticsProperties.TestTag,
                                ) {
                                    ""
                                }
                                .startsWith("bulk-row-")
                        },
                    )
                    .fetchSemanticsNodes()
                    .size
            assertTrue("Rendered $rendered recipient rows", rendered in 1..30)
            compose.onNodeWithTag("bulk-recipient-search").performTextInput("08050001000")
            dismissKeyboard()
            compose.onNodeWithText("Showing 1 of 1,000 recipients").assertExists()
            compose.onNodeWithContentDescription("Edit Demo recipient 1000").performClick()
            compose
                .onNodeWithTag("bulk-recipients-list")
                .performScrollToNode(hasTestTag("bulk-recipient-amount"))
            compose.onNodeWithTag("bulk-recipient-amount").performTextReplacement("125.25")
            dismissKeyboard()
            compose.onNodeWithText("Review & pay").performClick()
            compose.onNodeWithText("Pay ₦100,025.25 · Demo").assertIsEnabled()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Pay ₦100,025.25 · Demo").performClick()
            waitForState {
                it.session.batches
                    .firstOrNull { batch -> batch.recipients.size == 1000 }
                    ?.successfulCount
                    ?.let { count -> count >= 25 } == true
            }
            send(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
            waitForState { it.session.offline && !it.isProcessing }
            val paused = state()
            val batch = paused.session.batches.first { it.recipients.size == 1000 }
            assertTrue(batch.hasPending)
            assertTrue(batch.reservedMinorUnits > 0)
            send(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOnline))
            waitForState { !it.session.offline }
            assertEquals(batch, state().session.batches.first { it.id == batch.id })
            assertEquals(paused.session.balance, state().session.balance)
            compose.onNodeWithText("Resume demo").performClick()
            waitForState(60_000) {
                !it.isProcessing &&
                    it.session.batches
                        .first { batch -> batch.recipients.size == 1000 }
                        .failedCount == 1
            }
            compose.onNodeWithText("Review 1 failed payment").performClick()
            compose.onNodeWithText("Retry ₦125.25 · Demo").performClick()
            waitForState {
                !it.isProcessing &&
                    it.session.batches
                        .first { batch -> batch.recipients.size == 1000 }
                        .successfulCount == 1000
            }
            assertEquals(2_842_475L, state().session.balance)
            compose.onNodeWithText("View batch history").performClick()
            compose.onNodeWithText("Batches").assertIsSelected()
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    /** Uses the lifecycle owner and generated Hilt graph; no test factory or service locator. */
    private fun owner() = ViewModelProvider(compose.activity)[AxiaViewModel::class.java]

    /** Reads state on Main so tests never race the presentation owner. */
    private fun state(): AxiaUiState {
        lateinit var value: AxiaUiState
        compose.runOnUiThread { value = owner().uiState.value }
        return value
    }

    /** Waits for asynchronous disk work without assuming Compose idleness includes all IO. */
    private fun waitForState(timeout: Long = 15_000, predicate: (AxiaUiState) -> Boolean) {
        compose.waitUntil(timeout) { predicate(state()) }
    }

    /** Emits ordinary typed intents only; the ViewModel still owns all processing. */
    private fun send(intent: AxiaIntent) {
        compose.runOnUiThread { owner().accept(intent) }
    }

    /** Keeps subsequent buttons reachable on the small emulator after text editing. */
    private fun dismissKeyboard() {
        compose.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
}
