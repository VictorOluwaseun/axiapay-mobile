package com.kesofty.axiapay.data.wallet

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.wallet.*
import java.math.BigInteger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Reads only GET wallets/me with the explicit bearer; no local wallet cache or creation fallback.
 */
class HttpWalletRepository(private val http: ApiHttpClient) : WalletRepository {
    override val apiIdentity: String
        get() = http.apiIdentity

    /** Decode exact string amounts and keep HTTP/storage models inside this adapter. */
    override suspend fun currentWallet(accessToken: String, currency: String): WalletSummary {
        try {
            val json =
                requireNotNull(
                    http.request(
                        "wallets/me",
                        200,
                        bearer = accessToken,
                        query = mapOf("currency" to currency),
                    ),
                )
            val id = string(json, "id")
            require(UUID.fromString(id).toString() == id.lowercase(java.util.Locale.ROOT))
            require(string(json, "currency") == currency)
            val status =
                when (string(json, "walletStatus")) {
                    "active" -> WalletStatus.Active
                    "frozen" -> WalletStatus.Frozen
                    "closed" -> WalletStatus.Closed
                    else -> error("Unknown wallet status")
                }
            val available = amount(json, "availableMinorUnits")
            val reserved = amount(json, "reservedMinorUnits")
            val total = amount(json, "totalMinorUnits")
            require(total == available + reserved)
            return WalletSummary(id, currency, status, available, reserved, total)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiRequestFailure) {
            val issue =
                when {
                    failure.kind == ApiFailureKind.Configuration -> WalletIssue.Configuration
                    failure.kind == ApiFailureKind.Network -> WalletIssue.Network
                    failure.kind == ApiFailureKind.InvalidResponse -> WalletIssue.InvalidResponse
                    failure.status == 401 -> WalletIssue.InvalidSession
                    failure.status == 404 -> WalletIssue.Missing
                    else -> WalletIssue.Unavailable
                }
            throw WalletFailure(issue)
        } catch (failure: Exception) {
            throw WalletFailure(WalletIssue.InvalidResponse)
        }
    }

    /** Reject implicit JSON conversion at the transport trust boundary. */
    private fun string(json: JSONObject, key: String): String =
        json.get(key) as? String ?: error("Expected string")

    /** Read canonical nonnegative decimal minor units without floating-point or Long overflow. */
    private fun amount(json: JSONObject, key: String): BigInteger {
        val value = string(json, key)
        require(Regex("0|[1-9][0-9]{0,19}").matches(value))
        return value.toBigInteger()
    }
}
