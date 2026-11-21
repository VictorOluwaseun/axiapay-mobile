package com.kesofty.axiapay

import com.kesofty.axiapay.domain.auth.AccountIdentity
import com.kesofty.axiapay.domain.dashboard.*
import com.kesofty.axiapay.wallet.data.WalletRepositoryImpl
import com.kesofty.axiapay.wallet.data.local.WalletLocalDataSource
import com.kesofty.axiapay.wallet.data.remote.WalletRemoteDataSource
import com.kesofty.axiapay.wallet.domain.WalletSummary

/** Deterministic owned snapshots for use-case tests; Room persistence is exercised on device. */
internal class InMemoryDashboardRepository : DashboardRepository, WalletLocalDataSource {
    private val records = mutableMapOf<Pair<String, String>, DashboardSnapshot>()

    /** Read only the selected API/account. */
    override suspend fun read(apiIdentity: String, accountId: String) =
        records[apiIdentity to accountId]

    /** Wire the production cache policy to the same account snapshot store used by this test. */
    fun walletRepository(remote: WalletRemoteDataSource = RecordingWalletRemoteDataSource()) =
        WalletRepositoryImpl(remote, this)

    /** Read only a matching account and currency. */
    override suspend fun readWallet(apiIdentity: String, accountId: String, currency: String) =
        read(apiIdentity, accountId)?.wallet?.takeIf { it.currency == currency }

    /** An identity refresh retains its cached wallet. */
    override suspend fun saveAccount(apiIdentity: String, account: AccountIdentity) {
        val key = apiIdentity to account.id
        records[key] = DashboardSnapshot(account, records[key]?.wallet)
    }

    /** A wallet belongs to a previously stored identity. */
    override suspend fun saveWallet(apiIdentity: String, accountId: String, wallet: WalletSummary) {
        val key = apiIdentity to accountId
        records[key] = requireNotNull(records[key]).copy(wallet = wallet)
    }

    /** A missing response removes only the matching currency. */
    override suspend fun clearWallet(apiIdentity: String, accountId: String, currency: String) {
        val key = apiIdentity to accountId
        records[key]
            ?.takeIf { it.wallet?.currency == currency }
            ?.let { records[key] = it.copy(wallet = null) }
    }

    /** Account removal leaves all other scopes untouched. */
    override suspend fun clear(apiIdentity: String, accountId: String) {
        records.remove(apiIdentity to accountId)
    }
}
