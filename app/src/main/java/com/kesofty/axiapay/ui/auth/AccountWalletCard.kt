package com.kesofty.axiapay.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.wallet.*
import com.kesofty.axiapay.presentation.auth.AuthUiState
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.ui.walletIssueMessage
import com.kesofty.axiapay.ui.walletMoney
import com.kesofty.axiapay.ui.walletStatusLabel

/**
 * Render the signed-in account's server wallet independently of the payment preview's demo state.
 */
@Composable
internal fun AccountWalletCard(state: AuthUiState, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, border = controlBorder()) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp).testTag("account-wallet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.wallet_title), style = MaterialTheme.typography.titleLarge)
            if (state.isWalletRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.wallet_loading),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            when (val result = state.wallet) {
                is WalletLookupResult.Loaded -> WalletDetails(result.wallet)
                is WalletLookupResult.Failed -> {
                    val message = walletIssueMessage(result.issue)
                    Text(
                        stringResource(message),
                        color = Muted,
                        modifier =
                            Modifier.testTag("wallet-error").semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
                null -> Text(stringResource(R.string.wallet_unavailable), color = Muted)
            }
            TextButton(
                onRetry,
                enabled = !state.isProcessing,
                modifier = Modifier.heightIn(min = 48.dp).testTag("wallet-refresh"),
            ) {
                Text(
                    stringResource(
                        if (state.wallet is WalletLookupResult.Loaded) R.string.wallet_refresh
                        else R.string.wallet_retry,
                    ),
                )
            }
        }
    }
}

/** Present exact balances and server lifecycle status without making spending decisions. */
@Composable
private fun WalletDetails(wallet: WalletSummary) {
    Text(
        stringResource(R.string.wallet_available),
        style = MaterialTheme.typography.bodyMedium,
        color = Muted,
    )
    Text(
        walletMoney(wallet.availableMinorUnits, wallet.currency),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.testTag("wallet-available"),
    )
    Text(
        stringResource(
            R.string.wallet_reserved,
            walletMoney(wallet.reservedMinorUnits, wallet.currency),
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("wallet-reserved"),
    )
    Text(
        stringResource(R.string.wallet_total, walletMoney(wallet.totalMinorUnits, wallet.currency)),
        style = MaterialTheme.typography.bodyMedium,
    )
    val status = walletStatusLabel(wallet.status)
    Text(
        stringResource(status),
        style = MaterialTheme.typography.labelLarge,
        color = Pine,
        modifier = Modifier.testTag("wallet-status"),
    )
    Text(
        stringResource(R.string.wallet_id, wallet.id),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        modifier = Modifier.testTag("wallet-id"),
    )
}
