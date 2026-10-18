package com.kesofty.axiapay.data.database

import androidx.room.*

/** Scoped identity/wallet SQL; upserts preserve child wallets when account details refresh. */
@Dao
interface DashboardDao {
    /** Read only the captured API/account identity. */
    @Query("SELECT * FROM accounts WHERE api_identity = :apiIdentity AND account_id = :accountId")
    suspend fun account(apiIdentity: String, accountId: String): AccountEntity?

    /** Read one currency under the same account scope. */
    @Query(
        "SELECT * FROM wallet_summaries WHERE api_identity = :apiIdentity AND account_id = :accountId AND currency = :currency",
    )
    suspend fun wallet(
        apiIdentity: String,
        accountId: String,
        currency: String,
    ): WalletSummaryEntity?

    /** Update identity in place; REPLACE would cascade-delete cached wallet rows. */
    @Upsert suspend fun saveAccount(account: AccountEntity)

    /** Commit all displayed amounts as one server snapshot. */
    @Upsert suspend fun saveWallet(wallet: WalletSummaryEntity)

    /** Invalidate one currency after the API confirms its wallet no longer exists. */
    @Query(
        "DELETE FROM wallet_summaries WHERE api_identity = :apiIdentity AND account_id = :accountId AND currency = :currency",
    )
    suspend fun clearWallet(apiIdentity: String, accountId: String, currency: String)

    /** Remove owned identity and its wallets through the foreign key. */
    @Query("DELETE FROM accounts WHERE api_identity = :apiIdentity AND account_id = :accountId")
    suspend fun clear(apiIdentity: String, accountId: String)
}
