package com.kesofty.axiapay

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.imports.AndroidRecipientImportRepository
import com.kesofty.axiapay.data.imports.RecipientDocumentParser
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Exercises Android content grants, actual format decoders and import UI through the Hilt owner.
 */
class BulkImportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val resolver
        get() = instrumentation.targetContext.contentResolver

    /**
     * Publishes synthetic input as an app-owned download and removes it even on assertion failure.
     */
    private fun withDocument(extension: String, block: (Uri) -> Unit) {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "axiapay-test.$extension")
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    when (extension) {
                        "xls" -> "application/vnd.ms-excel"
                        "xlsx" ->
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        else -> "text/csv"
                    },
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(uri)!!.use { output ->
                instrumentation.context.assets.open("imports/recipients.$extension").use {
                    it.copyTo(output)
                }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            block(uri)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    /** The same three files must decode on Android, not merely on the desktop JVM. */
    @Test
    fun contentResolverReadsCsvXlsxAndXls() {
        val repository =
            AndroidRecipientImportRepository(resolver, Dispatchers.IO, RecipientDocumentParser())
        for (extension in listOf("csv", "xlsx", "xls")) withDocument(extension) { uri ->
            val result = runBlocking { repository.read(uri.toString()) }
            assertTrue("$extension: $result", result is ImportDocumentResult.Success)
            val document = (result as ImportDocumentResult.Success).document
            assertEquals("08030000001", document.rows[1].cells[1].text)
            assertEquals("1500.25", document.rows[1].cells[3].text)
        }
        assertTrue(
            runBlocking { repository.read("file:///no-file.csv") } is ImportDocumentResult.Failure,
        )
    }

    /**
     * A local Excel import works offline, survives rotation, and still requires explicit review.
     */
    @Test
    fun previewSelectionAndRotationKeepDraftAndWalletIntact() {
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        compose.onNodeWithText("Payments").performClick()
        compose.onNodeWithTag("bulk-entry").performClick()
        compose.onNodeWithTag("bulk-service-Airtime").performScrollTo().performClick()
        compose.onNodeWithTag("bulk-import").assertIsDisplayed().performClick()
        withDocument("xls") { uri ->
            compose.runOnUiThread {
                val vm = ViewModelProvider(compose.activity)[AxiaViewModel::class.java]
                vm.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
                val id = vm.uiState.value.navigation.bulk.importState!!.id
                vm.accept(AxiaIntent.Bulk(BulkIntent.ImportFile(id, uri.toString())))
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithText("2 selected · ₦2,500.25")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("2 selected · ₦2,500.25").assertExists()
            compose
                .onNodeWithTag("bulk-import-list")
                .performScrollToNode(hasTestTag("import-row-2"))
            compose.onNodeWithTag("import-row-2").performClick()
            compose.onNodeWithText("1 selected · ₦1,000.00").assertExists()
            compose.onNodeWithText("Add selected (1)").performClick()
            compose.runOnUiThread {
                val state =
                    ViewModelProvider(compose.activity)[AxiaViewModel::class.java].uiState.value
                assertEquals(1, state.navigation.bulk.draft!!.recipients.size)
                assertEquals(
                    "08030000002",
                    state.navigation.bulk.draft!!.recipients.single().recipientReference,
                )
                assertEquals(12_845_000L, state.session.balance)
            }
            compose.onNodeWithText("Review & pay").performClick()
            compose.onNodeWithText("Pay ₦1,000.00 · Demo").assertIsNotEnabled()
        }
    }
}
