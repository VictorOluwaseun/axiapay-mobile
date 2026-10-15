package com.kesofty.axiapay.ui.bulk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.BulkBatch
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*

/**
 * Dedicated bulk screens share the payment owner and theme; inputs and execution remain external.
 */
@Composable
fun BulkPaymentFlow(state: AxiaUiState, onIntent: (AxiaIntent) -> Unit) {
    val flow = state.navigation.bulk
    val screen = flow.screen ?: return
    val batch = state.session.batches.find { it.id == flow.batchId }
    val emit: (BulkIntent) -> Unit = { onIntent(AxiaIntent.Bulk(it)) }
    BackHandler { emit(BulkIntent.Back) }
    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                BulkTopBar(flow, emit)
                key(screen, flow.batchId, flow.importState?.id) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (screen) {
                            BulkScreen.Recipients ->
                                BulkRecipients(flow, state.session.offline, emit)
                            BulkScreen.Import -> BulkImportScreen(flow, emit)
                            BulkScreen.Services -> BulkServicePicker(flow, emit)
                            BulkScreen.Review,
                            BulkScreen.RetryReview -> BulkReview(flow, state.session, batch, emit)
                            BulkScreen.Progress ->
                                BulkProgress(
                                    flow,
                                    batch,
                                    state.session.offline,
                                    state.isProcessing,
                                    emit,
                                )
                        }
                    }
                }
                BulkFooter(state, batch, emit) { onIntent(AxiaIntent.OpenService("Add money")) }
            }
        }
    }
}

/** A quiet service title replaces setup steps; Back retains all service drafts. */
@Composable
private fun BulkTopBar(flow: BulkFlowState, emit: (BulkIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton({ emit(BulkIntent.Back) }) {
            AxiaIcon(
                Glyph.ArrowRight,
                Modifier.rotate(180f),
                description = stringResource(R.string.bulk_back),
            )
        }
        Text(
            if (flow.screen == BulkScreen.Recipients && flow.draft != null)
                serviceBulkTitle(flow.draft.service)
            else stringResource(R.string.bulk_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.bulk_demo),
            style = MaterialTheme.typography.labelMedium,
            color = Pine,
            modifier =
                Modifier.background(Mint, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        IconButton({ emit(BulkIntent.Close) }) {
            AxiaIcon(Glyph.Close, description = stringResource(R.string.bulk_close))
        }
    }
}

/** Keeps the next action visible while long recipient lists and large text remain scrollable. */
@Composable
private fun BulkFooter(
    state: AxiaUiState,
    batch: BulkBatch?,
    emit: (BulkIntent) -> Unit,
    addMoney: () -> Unit,
) {
    val flow = state.navigation.bulk
    if (flow.screen == BulkScreen.Services) return
    val focus = LocalFocusManager.current
    Surface(color = Color.White, shadowElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (flow.screen) {
                BulkScreen.Import -> BulkImportFooter(flow, emit)
                BulkScreen.Recipients -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.bulk_full_total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                        Text(
                            bulkMoney(flow.previewTotalMinorUnits),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    PrimaryButton(
                        stringResource(R.string.bulk_review_button),
                        enabled =
                            !state.isProcessing &&
                                (flow.draft?.recipients?.isNotEmpty() == true ||
                                    flow.editor?.recipientReference?.isNotBlank() == true),
                    ) {
                        focus.clearFocus()
                        emit(BulkIntent.Review)
                    }
                }
                BulkScreen.Review,
                BulkScreen.RetryReview -> BulkConfirmFooter(state, batch, emit, addMoney)
                BulkScreen.Progress -> BulkProgressFooter(state, batch, emit)
                BulkScreen.Services,
                null -> Unit
            }
        }
    }
}

/** Explicitly reviews new reservations or failed-only retries, with an actionable funding path. */
@Composable
private fun BulkConfirmFooter(
    state: AxiaUiState,
    batch: BulkBatch?,
    emit: (BulkIntent) -> Unit,
    addMoney: () -> Unit,
) {
    val retry = state.navigation.bulk.screen == BulkScreen.RetryReview
    val total =
        if (retry) batch?.failedMinorUnits ?: 0
        else state.navigation.bulk.validation?.totalMinorUnits ?: 0
    if (state.session.balance < total) {
        PrimaryButton(
            stringResource(R.string.bulk_add_money),
            enabled = !state.isProcessing && !state.session.offline,
            onClick = addMoney,
        )
        return
    }
    val valid =
        if (retry) batch != null && batch.failedCount > 0 && !batch.hasPending
        else state.navigation.bulk.validation?.valid == true
    PrimaryButton(
        stringResource(
            if (retry) R.string.bulk_retry_confirm else R.string.bulk_confirm,
            bulkMoney(total),
        ),
        enabled = valid && !state.isProcessing && !state.session.offline,
    ) {
        emit(if (retry) BulkIntent.ConfirmRetry else BulkIntent.Submit)
    }
}

/** Recovery is always explicit; navigating to Activity leaves the current demo run undisturbed. */
@Composable
private fun BulkProgressFooter(state: AxiaUiState, batch: BulkBatch?, emit: (BulkIntent) -> Unit) {
    when {
        batch?.hasPending == true && !state.isProcessing ->
            PrimaryButton(stringResource(R.string.bulk_resume), enabled = !state.session.offline) {
                emit(BulkIntent.Resume)
            }
        batch != null && !batch.hasPending && batch.failedCount > 0 ->
            PrimaryButton(
                pluralStringResource(
                    R.plurals.bulk_retry_failed,
                    batch.failedCount,
                    batch.failedCount,
                ),
                enabled = !state.isProcessing,
            ) {
                emit(BulkIntent.ReviewRetry)
            }
        else -> Unit
    }
    TextButton(
        { emit(BulkIntent.History) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            stringResource(R.string.bulk_history_button),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
