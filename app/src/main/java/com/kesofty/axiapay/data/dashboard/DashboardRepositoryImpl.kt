package com.kesofty.axiapay.data.dashboard

import androidx.room.withTransaction
import com.kesofty.axiapay.data.database.*
import com.kesofty.axiapay.domain.auth.AccountIdentity
import com.kesofty.axiapay.domain.dashboard.*
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

    /**
     * Commit identity only when its display content changes, retaining wallet and catalogue
     * children.
     */
    override suspend fun saveAccount(apiIdentity: String, account: AccountIdentity) {
        database.withTransaction {
            val previous = database.dashboard().account(apiIdentity, account.id)?.toAccount()
            if (previous != account) {
                database
                    .dashboard()
                    .saveAccount(AccountEntity.fromAccount(apiIdentity, account, clock.millis()))
            }
        }
    }

    /** Cascade deletion is limited to the supplied API/account. */
    override suspend fun clear(apiIdentity: String, accountId: String) {
        database.dashboard().clear(apiIdentity, accountId)
    }
}
