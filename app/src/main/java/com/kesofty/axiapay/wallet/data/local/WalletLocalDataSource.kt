package com.kesofty.axiapay.wallet.data.local

import com.kesofty.axiapay.wallet.domain.WalletSummary

/** Persisted display balances keyed by API, account and currency. */
interface WalletLocalDataSource {
    /** Read only the selected currency under this owner. */
    suspend fun readWallet(apiIdentity: String, accountId: String, currency: String): WalletSummary?

    /** Commit the server's exact amounts together. */
    suspend fun saveWallet(apiIdentity: String, accountId: String, wallet: WalletSummary)

    /** Evict a balance the server reports missing. */
    suspend fun clearWallet(apiIdentity: String, accountId: String, currency: String)
}
