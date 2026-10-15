package com.kesofty.axiapay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.PrimaryButton
import com.kesofty.axiapay.ui.theme.*

/**
 * Blocks interactions until saved wallet and drafts load, without pretending a failure is empty
 * data.
 */
@Composable
internal fun SessionRestoration(state: AxiaUiState, emit: (AxiaIntent) -> Unit) {
    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(
                    if (state.restorationFailed) R.string.bulk_restore_failed
                    else R.string.bulk_restoring,
                ),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (state.restorationFailed) {
                Text(
                    stringResource(R.string.bulk_restore_recovery),
                    style = MaterialTheme.typography.bodyLarge,
                )
                PrimaryButton(stringResource(R.string.bulk_restore_retry)) {
                    emit(AxiaIntent.RetryRestore)
                }
            } else CircularProgressIndicator(color = Pine)
        }
    }
}
