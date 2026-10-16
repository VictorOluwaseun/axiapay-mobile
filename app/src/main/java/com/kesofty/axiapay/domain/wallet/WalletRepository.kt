package com.kesofty.axiapay.domain.wallet

/** Main-safe, cancellable current-account reads; the server derives ownership from the bearer. */
interface WalletRepository {
    /** Same normalized API identity as authentication; credentials must never cross API origins. */
    val apiIdentity: String

    /**
     * Retrieve an existing wallet for the exact currency; no create endpoint or automatic retry.
     */
    suspend fun currentWallet(accessToken: String, currency: String): WalletSummary
}
