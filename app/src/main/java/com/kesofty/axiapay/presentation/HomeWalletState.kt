package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.domain.wallet.WalletIssue
import com.kesofty.axiapay.domain.wallet.WalletSummary

/** Account-owned Home projection; only Demo permits the dashboard to read the simulated ledger. */
sealed interface HomeWalletState {
    /** Selected exclusively by the demo account workflow at composition. */
    data object Demo : HomeWalletState

    /** No verified account is available, even when the payment preview can be explored. */
    data object SignedOut : HomeWalletState

    /** A current session/wallet read is pending; no earlier balance is substituted. */
    data object Loading : HomeWalletState

    /** Exact available funds come directly from this authenticated account's wallet snapshot. */
    data class Loaded(val wallet: WalletSummary) : HomeWalletState

    /** Missing/unavailable wallets remain explicit instead of falling back to sample money. */
    data class Failed(val issue: WalletIssue) : HomeWalletState
}
