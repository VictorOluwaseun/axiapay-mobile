package com.kesofty.axiapay.ui.bulk

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.RecipientSampleFormat
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.theme.*

/**
 * A keyed registration binds callbacks to one request across rotation. The effect is acknowledged
 * before launch; a new request gets separate launchers so late results cannot target another file.
 */
@Composable
internal fun SampleSavePicker(download: SampleDownloadState?, emit: (BulkIntent) -> Unit) {
    if (download == null) return
    val receive: (Uri?) -> Unit = { uri ->
        emit(
            BulkIntent.SampleDownload(
                SampleDownloadIntent.Destination(download.id, uri?.toString()),
            ),
        )
    }
    val csv =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(RecipientSampleFormat.Csv.mimeType),
            receive,
        )
    val excel =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(RecipientSampleFormat.Excel.mimeType),
            receive,
        )
    LaunchedEffect(download.id, download.phase) {
        if (download.phase != SampleDownloadPhase.LaunchPicker) return@LaunchedEffect
        emit(BulkIntent.SampleDownload(SampleDownloadIntent.PickerOpened(download.id)))
        try {
            when (download.sample.format) {
                RecipientSampleFormat.Csv -> csv.launch(download.sample.fileName)
                RecipientSampleFormat.Excel -> excel.launch(download.sample.fileName)
            }
        } catch (_: ActivityNotFoundException) {
            emit(BulkIntent.SampleDownload(SampleDownloadIntent.PickerUnavailable(download.id)))
        } catch (_: SecurityException) {
            emit(BulkIntent.SampleDownload(SampleDownloadIntent.PickerUnavailable(download.id)))
        }
    }
}

/** Uses stacked full-width actions so both formats remain readable at large font scales. */
@Composable
internal fun ImportSampleDownloads(state: BulkImportState, emit: (BulkIntent) -> Unit) {
    val download = state.sampleDownload
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(22.dp))
            .padding(20.dp)
            .testTag("import-sample-downloads"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.import_sample_title),
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
        )
        Text(
            stringResource(R.string.import_sample_copy),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        state.sampleFormats.forEach { format ->
            OutlinedButton(
                onClick = { emit(BulkIntent.SampleDownload(SampleDownloadIntent.Request(format))) },
                enabled = !state.loading && download?.busy != true,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("import-sample-${format.extension}"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Pine),
            ) {
                Text(
                    stringResource(
                        if (format == RecipientSampleFormat.Csv) R.string.import_sample_csv
                        else R.string.import_sample_excel,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (RecipientSampleFormat.Csv in state.sampleFormats)
            OutlinedButton(
                onClick = {
                    emit(
                        BulkIntent.SampleDownload(
                            SampleDownloadIntent.Request(RecipientSampleFormat.Csv, 1_000),
                        ),
                    )
                },
                enabled = !state.loading && download?.busy != true,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("import-sample-1000"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Pine),
            ) {
                Text(
                    stringResource(R.string.import_sample_large),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        SampleDownloadStatus(download)
    }
}

/** Announces file completion or recovery without implying that recipients were imported or paid. */
@Composable
private fun SampleDownloadStatus(download: SampleDownloadState?) {
    val message =
        when (download?.phase) {
            SampleDownloadPhase.Saving -> stringResource(R.string.import_sample_saving)
            SampleDownloadPhase.Saved -> stringResource(R.string.import_sample_saved)
            SampleDownloadPhase.Failed -> stringResource(R.string.import_sample_failed)
            else -> return
        }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (download.phase == SampleDownloadPhase.Failed) MaterialTheme.colorScheme.error
            else Pine,
        modifier =
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("import-sample-status"),
    )
}
