package com.kesofty.axiapay.wallet.data.remote

import com.kesofty.axiapay.wallet.domain.WalletSummary

/** Authenticated wallet balance endpoint for one configured API. */
interface WalletRemoteDataSource {
    /** Identifies the server that may receive the supplied credential. */
    val apiIdentity: String

    /** Reads and validates the current balance from the server. */
    suspend fun currentWallet(accessToken: String, currency: String): WalletSummary
}
