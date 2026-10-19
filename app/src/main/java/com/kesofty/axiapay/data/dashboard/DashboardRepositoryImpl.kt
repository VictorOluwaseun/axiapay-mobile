package com.kesofty.axiapay.data.dashboard

import androidx.room.withTransaction
import com.kesofty.axiapay.data.database.*
import com.kesofty.axiapay.domain.auth.AccountIdentity
import com.kesofty.axiapay.domain.dashboard.*
import com.kesofty.axiapay.domain.wallet.WalletSummary
import java.time.Clock

/**
 * Owns Room snapshot mapping and transactions; it never contacts providers or authorizes spending.
 */
class DashboardRepositoryImpl(private val database: AxiaDatabase, private val clock: Clock) :
    DashboardRepository {
    /** Read both records from one committed database snapshot. */
    override suspend fun read(apiIdentity: String, accountId: String): DashboardSnapshot? =
        database.withTransaction {
            val account =
                database.dashboard().account(apiIdentity, accountId) ?: return@withTransaction null
            DashboardSnapshot(
                account.toAccount(),
                database.dashboard().wallet(apiIdentity, accountId, "NGN")?.toWallet(),
            )
        }

    /** Upsert verified identity while retaining previously committed wallet data. */
    override suspend fun saveAccount(apiIdentity: String, account: AccountIdentity) {
        database
            .dashboard()
            .saveAccount(AccountEntity.fromAccount(apiIdentity, account, clock.millis()))
    }

    /** Persist the entire exact wallet response under its known account. */
    override suspend fun saveWallet(apiIdentity: String, accountId: String, wallet: WalletSummary) {
        database
            .dashboard()
            .saveWallet(
                WalletSummaryEntity.fromWallet(apiIdentity, accountId, wallet, clock.millis()),
            )
    }

    /** An authoritative missing response invalidates only this currency's old snapshot. */
    override suspend fun clearWallet(apiIdentity: String, accountId: String, currency: String) {
        database.dashboard().clearWallet(apiIdentity, accountId, currency)
    }

    /** Cascade deletion is limited to the supplied API/account. */
    override suspend fun clear(apiIdentity: String, accountId: String) {
        database.dashboard().clear(apiIdentity, accountId)
    }
}
