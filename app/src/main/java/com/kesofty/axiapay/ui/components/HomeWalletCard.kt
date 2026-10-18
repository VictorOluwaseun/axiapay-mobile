package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.layout.*
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
import com.kesofty.axiapay.domain.wallet.WalletStatus
import com.kesofty.axiapay.presentation.HomeWalletState
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.ui.walletIssueMessage
import com.kesofty.axiapay.ui.walletMoney
import com.kesofty.axiapay.ui.walletStatusLabel
import java.util.Locale

/** Uses the shared demo design with account-owned funds and caller-owned navigation/recovery. */
@Composable
internal fun HomeWalletCard(
    state: HomeWalletState,
    hidden: Boolean,
    offline: Boolean,
    onToggleHidden: () -> Unit,
    onAddMoney: () -> Unit,
    onSendMoney: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    DashboardWalletCard(
        title =
            stringResource(
                if (offline) R.string.home_wallet_last_received else R.string.wallet_available,
            ),
        badge = (state as? HomeWalletState.Loaded)?.wallet?.currency ?: "NGN",
        hidden = hidden,
        onToggleHidden = onToggleHidden,
        onAddMoney = onAddMoney,
        onSendMoney = onSendMoney,
        canHideBalance = state is HomeWalletState.Loaded,
        actionsEnabled = state is HomeWalletState.Loaded,
    ) {
        HomeWalletContent(state, hidden, offline)
        when (state) {
            is HomeWalletState.Failed ->
                TextButton(onRefresh, modifier = Modifier.testTag("home-wallet-refresh")) {
                    Text(stringResource(R.string.wallet_retry), color = Mint)
                }
            HomeWalletState.SignedOut ->
                TextButton(onOpenAccount, modifier = Modifier.testTag("home-wallet-account")) {
                    Text(stringResource(R.string.auth_sign_in), color = Mint)
                }
            else -> Unit
        }
    }
}

/** Shows exact verified money or explicit recovery, with no simulated or zero fallback. */
@Composable
private fun HomeWalletContent(state: HomeWalletState, hidden: Boolean, offline: Boolean) {
    when (state) {
        is HomeWalletState.Loaded -> {
            MoneyAmount(
                if (hidden) stringResource(R.string.home_wallet_hidden)
                else
                    walletMoney(
                        state.wallet.availableMinorUnits,
                        state.wallet.currency,
                        Locale.forLanguageTag("en-NG"),
                    ),
                color = Color.White,
                modifier =
                    Modifier.fillMaxWidth().padding(bottom = 5.dp).testTag("home-wallet-balance"),
            )
            WalletCardCaption(
                stringResource(
                    when {
                        offline -> R.string.home_wallet_offline_caption
                        state.wallet.status == WalletStatus.Active -> R.string.home_wallet_everyday
                        else -> walletStatusLabel(state.wallet.status)
                    },
                ),
                Modifier.testTag("home-wallet-status"),
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
        HomeWalletState.Demo -> error("The demo wallet has its own data source.")
    }
}
