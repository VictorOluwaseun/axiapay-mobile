package com.kesofty.axiapay.data.airtime

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.airtime.*
import java.math.BigInteger
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * First-party airtime DTO adapter; bearer headers and bounded uncached IO use the shared transport.
 */
class HttpAirtimeRepository(private val http: ApiHttpClient) : AirtimeRepository {
    override val apiIdentity: String
        get() =
            try {
                http.apiIdentity
            } catch (failure: ApiRequestFailure) {
                throw AirtimeFailure(AirtimeIssue.Configuration, failure)
            }

    /** Follow bounded pages with unchanged filters; never substitute a local product catalogue. */
    override suspend fun products(accessToken: String, currency: String): List<AirtimeProduct> =
        mapped {
            val products = mutableListOf<AirtimeProduct>()
            val seenCursors = mutableSetOf<String>()
            val seenProducts = mutableSetOf<String>()
            var cursor: String? = null
            for (page in 0 until 40) {
                val query =
                    mutableMapOf(
                        "serviceType" to "airtime",
                        "currency" to currency,
                        "limit" to "25",
                    )
                cursor?.let { query["afterId"] = it }
                val json =
                    requireNotNull(
                        http.request(
                            "service-catalog/products",
                            200,
                            bearer = accessToken,
                            query = query,
                        ),
                    )
                val rows = json.getJSONArray("products")
                require(rows.length() <= 25)
                for (index in 0 until rows.length()) {
                    val product = product(rows.getJSONObject(index), currency)
                    require(seenProducts.add(product.id))
                    products += product
                }
                cursor = nullableString(json, "nextCursor")
                if (cursor == null) {
                    return@mapped Collections.unmodifiableList(products.toList())
                }
                require(validId(cursor) && seenCursors.add(cursor))
            }
            throw AirtimeFailure(AirtimeIssue.InvalidResponse)
        }

    /** Serialize prepared values directly; quote creation never submits or uses demo funds. */
    override suspend fun quote(
        accessToken: String,
        walletId: String,
        input: AirtimeQuoteInput,
    ): AirtimeQuote = mapped {
        val body =
            JSONObject()
                .put("walletId", walletId)
                .put("productId", input.productId)
                .put("recipientPhoneNumber", input.recipientPhoneNumber)
        input.amountMinorUnits?.let { body.put("amountMinorUnits", it.toString()) }
        quote(requireNotNull(http.request("payments/quotes", 201, body, accessToken)), true)
    }

    /** Retrieve the exact original quote, including capability and expiry, without requoting. */
    override suspend fun getQuote(accessToken: String, quoteId: String): AirtimeQuote = mapped {
        val quote =
            quote(
                requireNotNull(http.request("payments/quotes/$quoteId", 200, bearer = accessToken)),
                true,
            )
        require(quote.id == quoteId)
        quote
    }

    /** A single consent POST contains only the original quote and request key. */
    override suspend fun submit(accessToken: String, request: AirtimeRequest): AirtimePayment =
        mapped {
            val body =
                JSONObject().put("quoteId", request.quoteId).put("requestKey", request.requestKey)
            payment(requireNotNull(http.request("payments", 200, body, accessToken)))
        }

    /**
     * A missing payment is unobserved progress, never evidence that another key is safe to spend.
     */
    override suspend fun findByRequestKey(
        accessToken: String,
        requestKey: String,
    ): AirtimePayment? {
        try {
            return mapped {
                payment(
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
        } catch (failure: AirtimeFailure) {
            if (failure.issue == AirtimeIssue.NotFound) {
                return null
            }
            throw failure
        }
    }

    /**
     * Explicit accepted-payment recovery keeps the same server identity and sends no new quote/key.
     */
    override suspend fun reconcile(accessToken: String, paymentId: String): AirtimePayment =
        mapped {
            payment(
                requireNotNull(
                    http.request("payments/$paymentId/reconcile", 200, JSONObject(), accessToken),
                ),
            )
        }

    /** Map public product terms only, rejecting malformed or non-airtime catalogue entries. */
    private fun product(json: JSONObject, currency: String): AirtimeProduct {
        require(string(json, "serviceType") == "airtime" && string(json, "currency") == currency)
        val minimum = amount(json, "minimumAmountMinorUnits")
        val maximum = amount(json, "maximumAmountMinorUnits")
        val step = amount(json, "amountStepMinorUnits")
        val fixed = nullableString(json, "fixedPriceMinorUnits")?.let(::minorUnits)
        require(minimum > BigInteger.ZERO && maximum >= minimum && step > BigInteger.ZERO)
        require(fixed == null || fixed in minimum..maximum)
        return AirtimeProduct(
            id(json, "id"),
            label(json, "displayName"),
            label(json, "networkCode"),
            currency,
            fixed,
            minimum,
            maximum,
            step,
            amount(json, "feeMinorUnits"),
            boolean(json, "isSubmissionAvailable"),
        )
    }

    /**
     * Read exact immutable review terms; progress quotes deliberately have no current-submission
     * capability.
     */
    private fun quote(json: JSONObject, withCapability: Boolean): AirtimeQuote {
        require(string(json, "serviceType") == "airtime" && string(json, "currency") == "NGN")
        val principal = amount(json, "principalMinorUnits")
        val fee = amount(json, "feeMinorUnits")
        val total = amount(json, "totalDebitMinorUnits")
        val created = Instant.parse(string(json, "createdAt"))
        val expires = Instant.parse(string(json, "expiresAt"))
        val recipient = string(json, "recipientPhoneNumber")
        require(principal > BigInteger.ZERO && total == principal + fee && expires > created)
        require(Regex("\\+[1-9][0-9]{7,14}").matches(recipient))
        return AirtimeQuote(
            id(json, "id"),
            id(json, "walletId"),
            id(json, "productId"),
            label(json, "displayName"),
            label(json, "networkCode"),
            recipient,
            "NGN",
            principal,
            fee,
            total,
            created,
            expires,
            withCapability && boolean(json, "isSubmissionAvailable"),
        )
    }

    /** Only documented committed status values may become progress or a final receipt. */
    private fun payment(json: JSONObject): AirtimePayment {
        val status =
            when (string(json, "paymentStatus")) {
                "awaiting_funds" -> AirtimePaymentStatus.AwaitingFunds
                "reserved" -> AirtimePaymentStatus.Reserved
                "pending" -> AirtimePaymentStatus.Pending
                "succeeded" -> AirtimePaymentStatus.Succeeded
                "failed" -> AirtimePaymentStatus.Failed
                else -> error("Unknown payment status")
            }
        return AirtimePayment(
            id(json, "id"),
            label(json, "requestKey"),
            status,
            quote(json.getJSONObject("quote"), false),
            Instant.parse(string(json, "createdAt")),
            nullableString(json, "submissionStartedAt")?.let(Instant::parse),
        )
    }

    /** Reject implicit primitive conversion at this transport boundary. */
    private fun string(json: JSONObject, key: String): String =
        json.get(key) as? String ?: error("Expected string")

    /** Keep user-visible server labels bounded and free of control characters. */
    private fun label(json: JSONObject, key: String): String =
        string(json, key).also {
            require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl))
        }

    /** Distinguish explicit null from missing or incorrectly typed protocol fields. */
    private fun nullableString(json: JSONObject, key: String): String? =
        if (json.get(key) == JSONObject.NULL) null else string(json, key)

    /** Preserve Boolean type rather than accepting string truth values. */
    private fun boolean(json: JSONObject, key: String): Boolean =
        json.get(key) as? Boolean ?: error("Expected boolean")

    /** Keep UUID parsing inside the adapter that owns server identity syntax. */
    private fun id(json: JSONObject, key: String): String =
        string(json, key).also { require(validId(it)) }.lowercase(Locale.ROOT)

    /** Reject shortened UUID encodings accepted by the JDK parser. */
    private fun validId(value: String): Boolean =
        UUID.fromString(value).toString() == value.lowercase(Locale.ROOT)

    /** Never coerce JSON numbers or use binary floating point for monetary values. */
    private fun amount(json: JSONObject, key: String): BigInteger = minorUnits(string(json, key))

    /** Accept canonical nonnegative SQL-bigint minor units only. */
    private fun minorUnits(value: String): BigInteger {
        require(Regex("0|[1-9][0-9]{0,18}").matches(value))
        return value.toBigInteger().also { require(it <= BigInteger.valueOf(Long.MAX_VALUE)) }
    }

    /** Redact upstream messages and preserve cancellation, including during error-body reads. */
    private suspend fun <T> mapped(operation: suspend () -> T): T {
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AirtimeFailure) {
            throw failure
        } catch (failure: ApiRequestFailure) {
            val issue =
                when {
                    failure.kind == ApiFailureKind.Configuration -> AirtimeIssue.Configuration
                    failure.kind == ApiFailureKind.Network -> AirtimeIssue.Network
                    failure.kind == ApiFailureKind.InvalidResponse -> AirtimeIssue.InvalidResponse
                    failure.status == 401 -> AirtimeIssue.SignInRequired
                    failure.code == "QUOTE_EXPIRED" -> AirtimeIssue.QuoteExpired
                    failure.code == "INSUFFICIENT_FUNDS" -> AirtimeIssue.InsufficientFunds
                    failure.code == "WALLET_RESTRICTED" -> AirtimeIssue.WalletRestricted
                    failure.code in setOf("PAYMENT_CONFLICT", "IDEMPOTENCY_CONFLICT") ->
                        AirtimeIssue.PaymentConflict
                    failure.code == "PRODUCT_UNAVAILABLE" -> AirtimeIssue.ProductUnavailable
                    failure.code == "INVALID_PAYMENT" -> AirtimeIssue.InvalidAmount
                    failure.status == 404 -> AirtimeIssue.NotFound
                    else -> AirtimeIssue.Unavailable
                }
            throw AirtimeFailure(issue, failure)
        } catch (failure: Exception) {
            throw AirtimeFailure(AirtimeIssue.InvalidResponse, failure)
        }
    }
}
