package com.kesofty.axiapay.data.purchases

import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.purchases.remote.PurchaseJsonCodec
import com.kesofty.axiapay.data.purchases.remote.mapPurchaseResponse
import com.kesofty.axiapay.domain.purchases.*
import org.json.JSONObject

/** Maps verified meter, quote and payment requests to the first-party purchase API. */
class HttpPurchaseRepository(private val http: ApiHttpClient) : PurchaseRepository {
    override val apiIdentity: String
        get() = mapPurchaseResponse { http.apiIdentity }

    /** Verify exact meter text with the selected server product; never send a payment here. */
    override suspend fun verifyMeter(
        accessToken: String,
        productId: String,
        accountNumber: String,
    ): MeterValidation = mapPurchaseResponse {
        val body = JSONObject().put("productId", productId).put("accountNumber", accountNumber)
        val result =
            PurchaseJsonCodec.meterValidation(
                requireNotNull(
                    http.request("payments/account-validations", 201, body, accessToken),
                ),
            )
        require(result.productId == productId && result.accountNumber == accountNumber)
        result
    }

    /** Serialize prepared values directly; quote creation never submits or uses demo funds. */
    override suspend fun quote(
        accessToken: String,
        walletId: String,
        input: PurchaseQuoteInput,
    ): PurchaseQuote = mapPurchaseResponse {
        val body = JSONObject().put("walletId", walletId).put("productId", input.productId)
        when (input.service) {
            PurchaseService.Electricity ->
                body
                    .put("accountValidationId", requireNotNull(input.verifiedMeter).validation.id)
                    .put("contactPhoneNumber", requireNotNull(input.contactPhoneNumber))
            PurchaseService.Airtime,
            PurchaseService.Data -> body.put("recipientPhoneNumber", input.recipientIdentifier)
        }
        input.amountMinorUnits?.let { body.put("amountMinorUnits", it.toString()) }
        PurchaseJsonCodec.quote(
            requireNotNull(http.request("payments/quotes", 201, body, accessToken)),
            true,
        )
    }

    /** Retrieve the exact original quote, including capability and expiry, without requoting. */
    override suspend fun getQuote(accessToken: String, quoteId: String): PurchaseQuote =
        mapPurchaseResponse {
            val quote =
                PurchaseJsonCodec.quote(
                    requireNotNull(
                        http.request("payments/quotes/$quoteId", 200, bearer = accessToken),
                    ),
                    true,
                )
            require(quote.id == quoteId)
            quote
        }

    /** A single consent POST contains only the original quote and request key. */
    override suspend fun submit(accessToken: String, request: PurchaseRequest): PurchasePayment =
        mapPurchaseResponse {
            val body =
                JSONObject().put("quoteId", request.quoteId).put("requestKey", request.requestKey)
            PurchaseJsonCodec.payment(
                requireNotNull(http.request("payments", 200, body, accessToken)),
            )
        }

    /**
     * A missing payment is unobserved progress, never evidence that another key is safe to spend.
     */
    override suspend fun findByRequestKey(
        accessToken: String,
        requestKey: String,
    ): PurchasePayment? {
        try {
            return mapPurchaseResponse {
                PurchaseJsonCodec.payment(
                    requireNotNull(
                        http.request(
                            "payments/by-request-key",
                            200,
                            bearer = accessToken,
                            query = mapOf("requestKey" to requestKey),
                        ),
                    ),
                )
            }
        } catch (failure: PurchaseFailure) {
            if (failure.issue == PurchaseIssue.NotFound) {
                return null
            }
            throw failure
        }
    }

    /**
     * Explicit accepted-payment recovery keeps the same server identity and sends no new quote/key.
     */
    override suspend fun reconcile(accessToken: String, paymentId: String): PurchasePayment =
        mapPurchaseResponse {
            PurchaseJsonCodec.payment(
                requireNotNull(
                    http.request("payments/$paymentId/reconcile", 200, JSONObject(), accessToken),
                ),
            )
        }
}
