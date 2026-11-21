package com.kesofty.axiapay.wallet.data.remote

import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.wallet.domain.FundingInput
import org.json.JSONObject

/** Sends authenticated funding requests to the configured API and returns its response bodies. */
class FundingRemoteDataSource(private val http: ApiHttpClient) {
    /** Identifies the API that owns these requests. */
    val apiIdentity: String
        get() = http.apiIdentity

    /** Fetch one bounded page of funding options and history. */
    suspend fun context(token: String, walletId: String, offset: Int): JSONObject =
        requireNotNull(
            http.request(
                "wallets/$walletId/funding-context",
                200,
                bearer = token,
                query = mapOf("offset" to offset.toString()),
            ),
        )

    /** Send the original logical key with the exact terms prepared by the use case. */
    suspend fun create(token: String, walletId: String, input: FundingInput): JSONObject {
        val body =
            JSONObject()
                .put("fundingOptionId", input.optionId)
                .put("amountMinorUnits", input.amountMinorUnits.toString())
                .put("currency", "NGN")
                .put("requestKey", input.requestKey)
        input.payerEmail?.let { body.put("payerEmail", it) }
        return requireNotNull(http.request("wallets/$walletId/funding-requests", 202, body, token))
    }

    /** Read the recorded status after restoration or selection. */
    suspend fun get(token: String, requestId: String): JSONObject =
        requireNotNull(http.request("wallet-funding/$requestId", 200, bearer = token))

    /** Add customer-supplied matching evidence to an existing request. */
    suspend fun submitReference(token: String, requestId: String, reference: String): JSONObject =
        requireNotNull(
            http.request(
                "wallet-funding/$requestId/reference",
                200,
                JSONObject().put("paymentReference", reference),
                token,
            ),
        )

    /** Explicitly request server reconciliation of the existing request. */
    suspend fun reconcile(token: String, requestId: String): JSONObject =
        requireNotNull(
            http.request("wallet-funding/$requestId/reconcile", 200, JSONObject(), token),
        )
}
