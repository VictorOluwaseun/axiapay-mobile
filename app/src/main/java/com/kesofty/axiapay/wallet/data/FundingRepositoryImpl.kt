package com.kesofty.axiapay.wallet.data

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.wallet.domain.*
import java.math.BigInteger
import java.net.URI
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Adapt the owner-scoped API into immutable funding models; all evidence and balances remain
 * server-owned.
 */
class FundingRepositoryImpl(private val http: ApiHttpClient) : FundingRepository {
    override val apiIdentity
        get() = http.apiIdentity

    /**
     * Decode a bounded page using strict values, never JSONObject's implicit number/string
     * coercion.
     */
    override suspend fun context(token: String, walletId: String, offset: Int): FundingContext =
        guarded {
            val json =
                requireNotNull(
                    http.request(
                        "wallets/$walletId/funding-context",
                        200,
                        bearer = token,
                        query = mapOf("offset" to offset.toString()),
                    ),
                )
            val options = json.getJSONArray("options")
            val requests = json.getJSONArray("requests")
            require(options.length() <= 100 && requests.length() <= 25)
            FundingContext(
                walletId,
                Collections.unmodifiableList(
                    List(options.length()) { index ->
                        val option = options.getJSONObject(index)
                        FundingOption(
                            text(option, "id"),
                            text(option, "displayName"),
                            text(option, "currency"),
                            amount(option, "minimumAmountMinorUnits"),
                            amount(option, "maximumAmountMinorUnits"),
                            option.opt("flow") == "bank_transfer",
                        )
                    },
                ),
                Collections.unmodifiableList(
                    List(requests.length()) { index ->
                        decode(requests.getJSONObject(index)).also {
                            require(it.walletId == walletId)
                        }
                    },
                ),
                json.opt("nextOffset").takeIf { it is Int } as? Int,
            )
        }

    /**
     * Serialize already prepared money directly, retaining the same logical identity on every
     * explicit retry.
     */
    override suspend fun create(
        token: String,
        walletId: String,
        input: FundingInput,
    ): FundingRequest = guarded {
        val body =
            JSONObject()
                .put("fundingOptionId", input.optionId)
                .put("amountMinorUnits", input.amountMinorUnits.toString())
                .put("currency", "NGN")
                .put("requestKey", input.requestKey)
        input.payerEmail?.let { body.put("payerEmail", it) }
        decode(requireNotNull(http.request("wallets/$walletId/funding-requests", 202, body, token)))
            .also {
                require(
                    it.walletId == walletId &&
                        it.optionId == input.optionId &&
                        it.amountMinorUnits == input.amountMinorUnits,
                )
            }
    }

    /** Read without replaying any command during restoration. */
    override suspend fun get(token: String, requestId: String) =
        readRequest(token, requestId, "", null)

    /** References help match deposits but cannot authorize credits. */
    override suspend fun submitReference(token: String, requestId: String, reference: String) =
        readRequest(token, requestId, "/reference", JSONObject().put("paymentReference", reference))

    /** Reconcile only after an explicit customer status check. */
    override suspend fun reconcile(token: String, requestId: String) =
        readRequest(token, requestId, "/reconcile", JSONObject())

    /** Enforce response correlation before publishing a server result. */
    private suspend fun readRequest(
        token: String,
        id: String,
        suffix: String,
        payload: JSONObject?,
    ): FundingRequest = guarded {
        decode(requireNotNull(http.request("wallet-funding/$id$suffix", 200, payload, token)))
            .also { require(it.id == id) }
    }

    /**
     * Decode only documented statuses and HTTPS hosted checkout; unknown responses never imply
     * success.
     */
    private fun decode(json: JSONObject): FundingRequest {
        val id = text(json, "fundingId")
        val walletId = text(json, "walletId")
        require(
            UUID.fromString(id).toString() == id &&
                UUID.fromString(walletId).toString() == walletId,
        )
        val status =
            when (text(json, "fundingStatus")) {
                "pending" -> FundingStatus.Pending
                "awaiting_transfer" -> FundingStatus.AwaitingTransfer
                "awaiting_confirmation" -> FundingStatus.AwaitingConfirmation
                "awaiting_credit" -> FundingStatus.AwaitingCredit
                "rejected" -> FundingStatus.Rejected
                "credited" -> FundingStatus.Credited
                else -> error("Unknown funding status")
            }
        val url = optionalText(json, "checkoutUrl")
        require(
            url == null ||
                (URI(url).scheme == "https" && URI(url).host != null && URI(url).userInfo == null),
        )
        val account =
            json.optJSONObject("transferInstructions")?.let {
                FundingBankAccount(
                    text(it, "bankName"),
                    text(it, "accountName"),
                    text(it, "accountNumber"),
                )
            }
        require(text(json, "currency") == "NGN")
        require(status != FundingStatus.Credited || optionalText(json, "operationId") != null)
        return FundingRequest(
            id,
            walletId,
            text(json, "fundingOptionId"),
            amount(json, "amountMinorUnits"),
            "NGN",
            status,
            account,
            url,
            optionalText(json, "customerReference"),
            optionalText(json, "reviewNote"),
        )
    }

    /** Preserve cancellation and map only bounded transport metadata. */
    private suspend fun <T> guarded(operation: suspend () -> T): T {
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiRequestFailure) {
            val issue =
                when {
                    failure.kind == ApiFailureKind.Network -> FundingIssue.Network
                    failure.status == 401 -> FundingIssue.SignInRequired
                    failure.status == 409 -> FundingIssue.Conflict
                    failure.status == 400 -> FundingIssue.InvalidInput
                    else -> FundingIssue.Unavailable
                }
            throw FundingFailure(issue)
        } catch (_: Exception) {
            throw FundingFailure(FundingIssue.InvalidResponse)
        }
    }

    /** Reject implicit JSON coercion at this external boundary. */
    private fun text(json: JSONObject, name: String) =
        json.get(name) as? String ?: error("Expected string")

    /** Allow missing/null fields without accepting wrong primitive types. */
    private fun optionalText(json: JSONObject, name: String): String? =
        if (!json.has(name) || json.isNull(name)) null else text(json, name)

    /** Parse exact canonical minor units without floating point arithmetic. */
    private fun amount(json: JSONObject, name: String): BigInteger {
        val value = text(json, name)
        require(Regex("[1-9][0-9]{0,18}").matches(value))
        return value.toBigInteger()
    }
}
