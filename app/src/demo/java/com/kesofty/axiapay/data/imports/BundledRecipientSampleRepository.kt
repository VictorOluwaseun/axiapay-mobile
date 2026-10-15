package com.kesofty.axiapay.data.imports

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import android.provider.DocumentsContract
import com.kesofty.axiapay.domain.*
import java.io.IOException
import kotlinx.coroutines.*

/**
 * Copies demo-only assets to a newly created SAF document on IO. No network, cache, payment
 * transaction or private recipient data participates. Failed writes attempt partial-file cleanup.
 */
class BundledRecipientSampleRepository(
    private val assets: AssetManager,
    private val resolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher,
) : RecipientSampleRepository {
    override val availableFormats = listOf(RecipientSampleFormat.Csv, RecipientSampleFormat.Excel)

    /** Closes both streams before reporting success; cancellation never becomes a saved result. */
    override suspend fun save(sample: RecipientSample, destination: String): SampleExportResult =
        withContext(dispatcher) {
            val uri = Uri.parse(destination)
            if (uri.scheme != "content") return@withContext SampleExportResult.Failed()
            try {
                ensureActive()
                val output =
                    resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Document provider did not open an output stream")
                output.use { destinationStream ->
                    if (sample.recipientCount == BulkLimits.MAX_RECIPIENTS) {
                        writeLargeSample(sample.service, destinationStream)
                    } else
                        assets.open("import-samples/${sample.fileName}").use {
                            it.copyTo(destinationStream)
                        }
                }
                ensureActive()
                SampleExportResult.Saved
            } catch (cancelled: CancellationException) {
                removePartialDocument(uri, cancelled)
                throw cancelled
            } catch (failure: Exception) {
                removePartialDocument(uri, failure)
                SampleExportResult.Failed(failure)
            }
        }

    /** Generates synthetic, unique identifiers locally; prices use the retained demo catalog. */
    private suspend fun writeLargeSample(service: BulkService, output: java.io.OutputStream) {
        val terms =
            when (service) {
                BulkService.Airtime -> listOf("MTN", "", "100")
                BulkService.Data -> listOf("MTN", "2 GB", "1000")
                BulkService.Electricity -> listOf("Ikeja Electric", "Prepaid", "100")
                BulkService.TV -> listOf("DStv", "Basic preview", "5000")
            }
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.appendLine("name,recipient,provider,product,amount,service")
        for (ordinal in 1..BulkLimits.MAX_RECIPIENTS) {
            currentCoroutineContext().ensureActive()
            val prefix =
                if (service == BulkService.Airtime || service == BulkService.Data) "0805"
                else "0125"
            val reference = prefix + ordinal.toString().padStart(7, '0')
            writer.appendLine(
                "Demo recipient $ordinal,$reference,${terms.joinToString(",")},${service.name}",
            )
        }
        writer.flush()
    }

    /** Only the caller's newly created document is eligible; cleanup failure retains its cause. */
    private suspend fun removePartialDocument(uri: Uri, cause: Throwable) {
        withContext(NonCancellable + dispatcher) {
            try {
                DocumentsContract.deleteDocument(resolver, uri)
            } catch (cleanupFailure: Exception) {
                cause.addSuppressed(cleanupFailure)
            }
        }
    }
}
