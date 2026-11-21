package com.kesofty.axiapay.wallet.data.local

import androidx.room.withTransaction
import com.kesofty.axiapay.data.database.AxiaDatabase
import com.kesofty.axiapay.data.database.WalletSummaryEntity
import com.kesofty.axiapay.wallet.domain.WalletSummary
import java.time.Clock

/** Stores balances in the existing Room dashboard database under their verified account. */
class RoomWalletLocalDataSource(private val database: AxiaDatabase, private val clock: Clock) :
    WalletLocalDataSource {
    /** Retrieve the most recent committed balance for this API, account and currency. */
    override suspend fun readWallet(
        apiIdentity: String,
        accountId: String,
        currency: String,
    ): WalletSummary? = database.dashboard().wallet(apiIdentity, accountId, currency)?.toWallet()

    /** Equal wallet refreshes leave both stored rows and UI observers untouched. */
    override suspend fun saveWallet(apiIdentity: String, accountId: String, wallet: WalletSummary) {
        database.withTransaction {
            val previous = readWallet(apiIdentity, accountId, wallet.currency)
            if (previous != wallet) {
                database
                    .dashboard()
                    .saveWallet(
                        WalletSummaryEntity.fromWallet(
                            apiIdentity,
                            accountId,
                            wallet,
                            clock.millis(),
                        ),
                    )
            }
        }
    }

    /** An authoritative missing response invalidates only this currency's old snapshot. */
    override suspend fun clearWallet(apiIdentity: String, accountId: String, currency: String) {
        database.dashboard().clearWallet(apiIdentity, accountId, currency)
    }
}
