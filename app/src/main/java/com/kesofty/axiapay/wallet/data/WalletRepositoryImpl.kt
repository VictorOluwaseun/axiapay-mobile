package com.kesofty.axiapay.wallet.data

import com.kesofty.axiapay.wallet.data.local.WalletLocalDataSource
import com.kesofty.axiapay.wallet.data.remote.WalletRemoteDataSource
import com.kesofty.axiapay.wallet.domain.WalletFailure
import com.kesofty.axiapay.wallet.domain.WalletIssue
import com.kesofty.axiapay.wallet.domain.WalletLookupResult
import com.kesofty.axiapay.wallet.domain.WalletRepository
import com.kesofty.axiapay.wallet.domain.WalletSummary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Selects fresh server reads for payment operations and account-scoped snapshots for balance
 * display.
 */
class WalletRepositoryImpl(
    private val remote: WalletRemoteDataSource,
    private val local: WalletLocalDataSource,
) : WalletRepository {
    /**
     * Account and purchase screens share this writer so older balances cannot overtake later
     * refreshes.
     */
    private val displayRefresh = Mutex()

    override val apiIdentity: String
        get() = remote.apiIdentity

    /** Funding and quote preparation always discover the current wallet on the server. */
    override suspend fun currentWallet(accessToken: String, currency: String): WalletSummary =
        remote.currentWallet(accessToken, currency)

    /** Provides a committed display snapshot while offline or awaiting refresh. */
    override suspend fun cachedWallet(accountId: String, currency: String): WalletSummary? =
        local.readWallet(apiIdentity, accountId, currency)

    /** Commit or invalidate only while the caller still owns the session that started this read. */
    override suspend fun refreshDisplayWallet(
        accountId: String,
        accessToken: String,
        currency: String,
        isSessionCurrent: suspend () -> Boolean,
    ): WalletLookupResult = displayRefresh.withLock {
        if (!isSessionCurrent())
            return@withLock WalletLookupResult.Failed(WalletIssue.InvalidSession)
        val result =
            try {
                WalletLookupResult.Loaded(remote.currentWallet(accessToken, currency))
            } catch (failure: WalletFailure) {
                WalletLookupResult.Failed(failure.issue)
            }
        currentCoroutineContext().ensureActive()
        if (!isSessionCurrent())
            return@withLock WalletLookupResult.Failed(WalletIssue.InvalidSession)
        currentCoroutineContext().ensureActive()
        when (result) {
            is WalletLookupResult.Loaded -> {
                local.saveWallet(apiIdentity, accountId, result.wallet)
                result
            }
            is WalletLookupResult.Failed -> balanceAfterFailure(accountId, currency, result)
        }
    }

    /**
     * A missing wallet evicts its row. A transient failure can still display the last saved
     * balance.
     */
    private suspend fun balanceAfterFailure(
        accountId: String,
        currency: String,
        failure: WalletLookupResult.Failed,
    ): WalletLookupResult =
        when (failure.issue) {
            WalletIssue.Missing -> {
                local.clearWallet(apiIdentity, accountId, currency)
                failure
            }
            WalletIssue.InvalidSession,
            WalletIssue.Configuration -> failure
            WalletIssue.Network,
            WalletIssue.Unavailable,
            WalletIssue.InvalidResponse ->
                cachedWallet(accountId, currency)?.let {
                    WalletLookupResult.Loaded(it, failure.issue)
                } ?: failure
        }
}
