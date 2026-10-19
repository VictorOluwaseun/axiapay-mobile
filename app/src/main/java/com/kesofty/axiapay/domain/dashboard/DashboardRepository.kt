package com.kesofty.axiapay.domain.dashboard

import com.kesofty.axiapay.domain.auth.AccountIdentity
import com.kesofty.axiapay.domain.wallet.WalletSummary

/**
 * Last committed display records; their presence never establishes current server authorization.
 */
data class DashboardSnapshot(val account: AccountIdentity, val wallet: WalletSummary?)

/** Durable API/account-scoped dashboard snapshots, with main-safe atomic persistence. */
interface DashboardRepository {
    /** Read identity and NGN wallet together without making any network request. */
    suspend fun read(apiIdentity: String, accountId: String): DashboardSnapshot?

    /** Commit a verified identity without deleting its existing wallet snapshot. */
    suspend fun saveAccount(apiIdentity: String, account: AccountIdentity)

    /** Replace the exact display amounts only after a successful wallet response. */
    suspend fun saveWallet(apiIdentity: String, accountId: String, wallet: WalletSummary)

    /** Remove a wallet snapshot after an authoritative missing-wallet response. */
    suspend fun clearWallet(apiIdentity: String, accountId: String, currency: String)

    /** Remove this account and its dependent wallet records, retaining public configuration. */
    suspend fun clear(apiIdentity: String, accountId: String)
}
