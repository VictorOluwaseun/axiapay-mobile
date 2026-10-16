package com.kesofty.axiapay.domain.wallet

import java.math.BigInteger

/** Display-only server snapshot; values are exact minor units and never authorize a payment. */
data class WalletSummary(
    val id: String,
    val currency: String,
    val status: WalletStatus,
    val availableMinorUnits: BigInteger,
    val reservedMinorUnits: BigInteger,
    val totalMinorUnits: BigInteger,
) {
    /** Keep financial details out of diagnostic interpolation. */
    override fun toString() = "WalletSummary(details=redacted)"
}

/** Server-controlled wallet lifecycle, independent of whether a cached display permits spending. */
enum class WalletStatus {
    Active,
    Frozen,
    Closed,
}

/** Safe retrieval failures; absent wallets are never created or replaced with demo balances. */
enum class WalletIssue {
    Missing,
    InvalidSession,
    Network,
    Unavailable,
    InvalidResponse,
    Configuration,
}

/**
 * Adapter failures contain classifications only, excluding response bodies and bearer credentials.
 */
class WalletFailure(val issue: WalletIssue) : Exception("Wallet lookup failed: $issue")

/**
 * A failed display fetch does not undo successful authentication or repeat credential redemption.
 */
sealed interface WalletLookupResult {
    /** Carries the authenticated session's server wallet for display. */
    data class Loaded(val wallet: WalletSummary) : WalletLookupResult

    /** Retains an explicit, retryable display failure without fabricating a wallet. */
    data class Failed(val issue: WalletIssue) : WalletLookupResult
}
