package com.kesofty.axiapay

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.imports.AndroidRecipientImportRepository
import com.kesofty.axiapay.data.imports.RecipientDocumentParser
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Verifies actual flavor composition, Android file writes and re-import of every demo sample. */
class SampleDownloadDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Enters the real import surface with the normal demo auth and payment navigation. */
    private fun openImport() {
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        compose.onNodeWithText("Payments").performClick()
        compose.onNodeWithTag("bulk-entry").performClick()
        compose.onNodeWithTag("bulk-service-Airtime").performScrollTo().performClick()
        compose.onNodeWithTag("bulk-import").assertIsDisplayed().performClick()
    }

    /**
     * Resolves the Hilt-backed lifecycle owner after rotations rather than retaining an Activity.
     */
    private fun viewModel() = ViewModelProvider(compose.activity)[AxiaViewModel::class.java]

    /** Demo contains both download actions and assets; other flavors expose neither. */
    @Test
    fun sampleCapabilityIsDemoOnly() {
        openImport()
        val assets =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .assets
                .list("import-samples")
                .orEmpty()
        if (BuildConfig.FLAVOR != "demo") {
            compose.onNodeWithTag("import-sample-downloads").assertDoesNotExist()
            assertTrue(assets.isEmpty())
            return
        }
        assertEquals(8, assets.size)
        compose
            .onNodeWithTag("bulk-import-list")
            .performScrollToNode(hasTestTag("import-sample-downloads"))
        compose.onNodeWithTag("import-sample-csv").assertIsEnabled()
        compose.onNodeWithTag("import-sample-xlsx").assertIsEnabled()
    }

    /**
     * Writes real documents offline, retains the grant request on rotation and re-imports bytes.
     */
    @Test
    fun savedSamplesReimportOfflineWithoutChangingPaymentState() {
        if (BuildConfig.FLAVOR != "demo") return
        openImport()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val reader =
            AndroidRecipientImportRepository(resolver, Dispatchers.IO, RecipientDocumentParser())
        compose.runOnUiThread {
            viewModel().accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        }
        compose.waitUntil(10_000) { viewModel().uiState.value.session.offline }
        val variants =
            RecipientSampleFormat.entries.map { it to 3 } + (RecipientSampleFormat.Csv to 1000)
        for (service in BulkService.entries) for ((format, count) in variants) {
            val sample = RecipientSample(service, format, count)
            val uri =
                requireNotNull(
                    resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, sample.fileName)
                            put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
                        },
                    ),
                )
            try {
                var requestId = ""
                lateinit var before: AxiaUiState
                compose.runOnUiThread {
                    val vm = viewModel()
                    vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(service)))
                    vm.accept(AxiaIntent.Bulk(BulkIntent.OpenImport))
                    before = vm.uiState.value
                    vm.accept(
                        AxiaIntent.Bulk(
                            BulkIntent.SampleDownload(SampleDownloadIntent.Request(format, count)),
                        ),
                    )
                    requestId = vm.uiState.value.navigation.bulk.importState!!.sampleDownload!!.id
                    vm.accept(
                        AxiaIntent.Bulk(
                            BulkIntent.SampleDownload(SampleDownloadIntent.PickerOpened(requestId)),
                        ),
                    )
                }
                compose.activityRule.scenario.recreate()
                compose.runOnUiThread {
                    assertEquals(
                        SampleDownloadPhase.ChoosingLocation,
                        viewModel()
                            .uiState
                            .value
                            .navigation
                            .bulk
                            .importState!!
                            .sampleDownload!!
                            .phase,
                    )
                    viewModel()
                        .accept(
                            AxiaIntent.Bulk(
                                BulkIntent.SampleDownload(
                                    SampleDownloadIntent.Destination(requestId, uri.toString()),
                                ),
                            ),
                        )
                }
                compose.waitUntil(10_000) {
                    viewModel().uiState.value.navigation.bulk.importState?.sampleDownload?.phase ==
                        SampleDownloadPhase.Saved
                }
                compose.runOnUiThread {
                    val state = viewModel().uiState.value
                    assertEquals(before.navigation.bulk.draft, state.navigation.bulk.draft)
                    assertEquals(before.navigation.bulk.editor, state.navigation.bulk.editor)
                    assertEquals(before.session, state.session)
                }
                val result = runBlocking { reader.read(uri.toString()) }
                assertTrue("$sample: $result", result is ImportDocumentResult.Success)
                val document = (result as ImportDocumentResult.Success).document
                assertEquals(count + 1, document.rows.size)
                val clock = java.time.Clock.systemUTC()
                val prepare =
                    PrepareRecipientImport(
                        RecipientImportRepository { error("No extra file read") },
                        PrepareBulkBatch(PreparePayment(clock), clock),
                    ) {
                        java.util.UUID.randomUUID().toString()
                    }
                val preview =
                    prepare.preview(document, BulkDraft("sample", service = service), null)
                        as RecipientImportResult.Ready
                assertEquals(count, preview.preview.rows.count { it.valid })
                assertTrue(
                    document.rows.drop(1).all {
                        it.cells[1].text.startsWith("0") && it.cells[1].kind == ImportCellKind.Text
                    },
                )
            } finally {
                resolver.delete(uri, null, null)
            }
        }
    }
}
