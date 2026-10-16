package com.kesofty.axiapay

import com.kesofty.axiapay.domain.wallet.*
import java.math.BigInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Isolated wallet port records bearer/currency selection and supports controlled failure or late
 * completion.
 */
internal class RecordingWalletRepository : WalletRepository {
    override var apiIdentity = "https://api.example.test/v1/"
    var wallet =
        WalletSummary(
            "123e4567-e89b-42d3-a456-426614174001",
            "NGN",
            WalletStatus.Active,
            BigInteger.valueOf(12500),
            BigInteger.valueOf(2500),
            BigInteger.valueOf(15000),
        )
    var requests = 0
    var token: String? = null
    var currency: String? = null
    var failure: WalletFailure? = null
    var beforeRead: (() -> Unit)? = null
    var gate: CompletableDeferred<Unit>? = null

    /**
     * Return only fixture values, optionally ignoring cancellation to exercise application
     * ownership guards.
     */
    override suspend fun currentWallet(accessToken: String, currency: String): WalletSummary {
        requests++
        token = accessToken
        this.currency = currency
        beforeRead?.invoke()
        gate?.let { withContext(NonCancellable) { it.await() } }
        failure?.let { throw it }
        return wallet
    }
}
