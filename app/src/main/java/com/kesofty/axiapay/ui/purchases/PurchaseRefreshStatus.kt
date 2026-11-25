package com.kesofty.axiapay.ui.purchases

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.presentation.purchases.*
import com.kesofty.axiapay.ui.theme.*

/** Quiet refresh feedback never takes ownership of the form or disables an existing catalogue. */
@Composable
internal fun PurchaseRefreshStatus(state: PurchaseUiState, onIntent: (PurchaseIntent) -> Unit) {
    if (
        state.screen !in
            setOf(PurchaseScreen.Form, PurchaseScreen.History, PurchaseScreen.LoadFailure)
    )
        return
    if (state.isRefreshing) {
        Row(
            Modifier.fillMaxWidth().testTag("purchase-refreshing"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Muted, strokeWidth = 2.dp)
            Text(
                stringResource(R.string.purchase_updating_options),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        return
    }
    if (state.refreshIssue == null || state.context?.products.isNullOrEmpty()) return
    Row(
        Modifier.fillMaxWidth().testTag("purchase-refresh-error"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.purchase_refresh_failed),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        TextButton({ onIntent(PurchaseIntent.Reload) }, enabled = !state.isProcessing) {
            Text(stringResource(R.string.telecom_catalogue_retry))
        }
    }
}
