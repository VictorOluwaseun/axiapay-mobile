package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.presentation.HomeWalletState
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.ui.walletIssueMessage
import com.kesofty.axiapay.ui.walletMoney
import com.kesofty.axiapay.ui.walletStatusLabel

/**
 * Render the authenticated Home balance without receiving any simulated balance or payment
 * mutation.
 */
@Composable
internal fun HomeWalletCard(
    state: HomeWalletState,
    hidden: Boolean,
    offline: Boolean,
    onToggleHidden: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    WalletCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (offline) R.string.home_wallet_last_received else R.string.wallet_available,
                ),
                color = Mint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onToggleHidden,
                enabled = state is HomeWalletState.Loaded,
                modifier = Modifier.size(48.dp),
            ) {
                AxiaIcon(
                    if (hidden) Glyph.EyeOff else Glyph.Eye,
                    Modifier.size(17.dp),
                    Mint,
                    stringResource(
                        if (hidden) R.string.home_wallet_show else R.string.home_wallet_hide,
                    ),
                )
            }
            Text(
                "NGN",
                color = Mint,
                style = MaterialTheme.typography.labelSmall,
                modifier =
                    Modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        HomeWalletContent(state, hidden)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state != HomeWalletState.SignedOut) {
                Button(
                    onRefresh,
                    enabled = state != HomeWalletState.Loading,
                    modifier =
                        Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-wallet-refresh"),
                    shape = RoundedCornerShape(14.dp),
                    colors =
                        ButtonDefaults.buttonColors(containerColor = Mint, contentColor = DeepPine),
                ) {
                    Text(
                        stringResource(
                            if (state is HomeWalletState.Failed) R.string.wallet_retry
                            else R.string.wallet_refresh,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Button(
                onOpenAccount,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-wallet-account"),
                shape = RoundedCornerShape(14.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = .1f),
                        contentColor = Color.White,
                    ),
            ) {
                Text(
                    stringResource(
                        if (state == HomeWalletState.SignedOut) R.string.auth_sign_in
                        else R.string.auth_account_label,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Display only a verified result; loading, missing and signed-out states contain no monetary
 * fallback.
 */
@Composable
private fun HomeWalletContent(state: HomeWalletState, hidden: Boolean) {
    when (state) {
        is HomeWalletState.Loaded -> {
            Text(
                if (hidden) stringResource(R.string.home_wallet_hidden)
                else walletMoney(state.wallet.availableMinorUnits, state.wallet.currency),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                modifier =
                    Modifier.fillMaxWidth().padding(bottom = 5.dp).testTag("home-wallet-balance"),
            )
            Text(
                stringResource(walletStatusLabel(state.wallet.status)),
                style = MaterialTheme.typography.bodySmall,
                color = Mint,
                modifier = Modifier.testTag("home-wallet-status"),
            )
        }
        HomeWalletState.Loading -> {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("home-wallet-loading"),
                color = Mint,
            )
            Text(
                stringResource(R.string.home_wallet_loading),
                color = Color.White,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HomeWalletState.SignedOut ->
            Text(
                stringResource(R.string.home_wallet_sign_in),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("home-wallet-signed-out"),
            )
        is HomeWalletState.Failed ->
            Text(
                stringResource(walletIssueMessage(state.issue)),
                color = Color.White,
                modifier =
                    Modifier.testTag("home-wallet-error").semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        HomeWalletState.Demo -> error("The demo wallet has its own renderer.")
    }
}
