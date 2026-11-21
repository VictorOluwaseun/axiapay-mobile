package com.kesofty.axiapay.wallet.domain

/** Main-safe, cancellable current-account reads; the server derives ownership from the bearer. */
interface WalletRepository {
    /** Same normalized API identity as authentication; credentials must never cross API origins. */
    val apiIdentity: String

    /**
     * Retrieve an existing wallet for the exact currency; no create endpoint or automatic retry.
     */
    suspend fun currentWallet(accessToken: String, currency: String): WalletSummary

    /** Read the account's last committed balance on this API for display. */
    suspend fun cachedWallet(accountId: String, currency: String): WalletSummary?

    /**
     * Refresh a display balance, checking the captured login before saving or invalidating its
     * cache.
     */
    suspend fun refreshDisplayWallet(
        accountId: String,
        accessToken: String,
        currency: String,
        isSessionCurrent: suspend () -> Boolean,
    ): WalletLookupResult
}
