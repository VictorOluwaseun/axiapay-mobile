package com.kesofty.axiapay.data.telecom

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.telecom.*
import java.math.BigInteger
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * First-party airtime/data DTO adapter; bearer headers and bounded uncached IO use the shared
 * transport.
 */
class HttpTelecomRepository(private val http: ApiHttpClient) : TelecomRepository {
    override val apiIdentity: String
        get() =
            try {
                http.apiIdentity
            } catch (failure: ApiRequestFailure) {
                throw TelecomFailure(TelecomIssue.Configuration, failure)
            }

    /** Follow bounded pages with unchanged filters; never substitute a local product catalogue. */
    override suspend fun products(
        accessToken: String,
        currency: String,
        service: TelecomService,
    ): List<TelecomProduct> = mapped {
        val products = mutableListOf<TelecomProduct>()
        val seenCursors = mutableSetOf<String>()
        val seenProducts = mutableSetOf<String>()
        var cursor: String? = null
        for (page in 0 until 40) {
            val query =
                mutableMapOf(
                    "serviceType" to service.name.lowercase(Locale.ROOT),
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
                val product = product(rows.getJSONObject(index), currency, service)
                require(seenProducts.add(product.id))
                products += product
            }
            cursor = nullableString(json, "nextCursor")
            if (cursor == null) {
                return@mapped Collections.unmodifiableList(products.toList())
            }
            require(validId(cursor) && seenCursors.add(cursor))
        }
        throw TelecomFailure(TelecomIssue.InvalidResponse)
    }

    /** Serialize prepared values directly; quote creation never submits or uses demo funds. */
    override suspend fun quote(
        accessToken: String,
        walletId: String,
        input: TelecomQuoteInput,
    ): TelecomQuote = mapped {
        val body =
            JSONObject()
                .put("walletId", walletId)
                .put("productId", input.productId)
                .put("recipientPhoneNumber", input.recipientPhoneNumber)
        input.amountMinorUnits?.let { body.put("amountMinorUnits", it.toString()) }
        quote(requireNotNull(http.request("payments/quotes", 201, body, accessToken)), true)
    }

    /** Retrieve the exact original quote, including capability and expiry, without requoting. */
    override suspend fun getQuote(accessToken: String, quoteId: String): TelecomQuote = mapped {
        val quote =
            quote(
                requireNotNull(http.request("payments/quotes/$quoteId", 200, bearer = accessToken)),
                true,
            )
        require(quote.id == quoteId)
        quote
    }

    /** A single consent POST contains only the original quote and request key. */
    override suspend fun submit(accessToken: String, request: TelecomRequest): TelecomPayment =
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
    ): TelecomPayment? {
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
        } catch (failure: TelecomFailure) {
            if (failure.issue == TelecomIssue.NotFound) {
                return null
            }
            throw failure
        }
    }

    /**
     * Explicit accepted-payment recovery keeps the same server identity and sends no new quote/key.
     */
    override suspend fun reconcile(accessToken: String, paymentId: String): TelecomPayment =
        mapped {
            payment(
                requireNotNull(
                    http.request("payments/$paymentId/reconcile", 200, JSONObject(), accessToken),
                ),
            )
        }

    /**
     * Map public terms, requiring fixed data pricing and plan metadata within the requested scope.
     */
    private fun product(
        json: JSONObject,
        currency: String,
        service: TelecomService,
    ): TelecomProduct {
        require(service(json) == service && string(json, "currency") == currency)
        val minimum = amount(json, "minimumAmountMinorUnits")
        val maximum = amount(json, "maximumAmountMinorUnits")
        val step = amount(json, "amountStepMinorUnits")
        val fixed = nullableString(json, "fixedPriceMinorUnits")?.let(::minorUnits)
        require(minimum > BigInteger.ZERO && maximum >= minimum && step > BigInteger.ZERO)
        require(fixed == null || fixed in minimum..maximum)
        val allowance = nullableLabel(json, "allowanceLabel")
        val validity = validitySeconds(json)
        if (service == TelecomService.Data) {
            require(fixed != null && fixed == minimum && fixed == maximum)
            require(allowance != null && validity != null)
        }
        return TelecomProduct(
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
            service,
            allowance,
            validity,
        )
    }

    /**
     * Read exact immutable review terms; progress quotes deliberately have no current-submission
     * capability.
     */
    private fun quote(json: JSONObject, withCapability: Boolean): TelecomQuote {
        val service = service(json)
        require(string(json, "currency") == "NGN")
        val principal = amount(json, "principalMinorUnits")
        val fee = amount(json, "feeMinorUnits")
        val total = amount(json, "totalDebitMinorUnits")
        val created = Instant.parse(string(json, "createdAt"))
        val expires = Instant.parse(string(json, "expiresAt"))
        val recipient = string(json, "recipientPhoneNumber")
        require(principal > BigInteger.ZERO && total == principal + fee && expires > created)
        require(Regex("\\+[1-9][0-9]{7,14}").matches(recipient))
        val allowance = nullableLabel(json, "allowanceLabel")
        val validity = validitySeconds(json)
        if (service == TelecomService.Data) {
            require(allowance != null && validity != null)
        }
        return TelecomQuote(
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
            service,
            allowance,
            validity,
        )
    }

    /** Only documented committed status values may become progress or a final receipt. */
    private fun payment(json: JSONObject): TelecomPayment {
        val status =
            when (string(json, "paymentStatus")) {
                "awaiting_funds" -> TelecomPaymentStatus.AwaitingFunds
                "reserved" -> TelecomPaymentStatus.Reserved
                "pending" -> TelecomPaymentStatus.Pending
                "succeeded" -> TelecomPaymentStatus.Succeeded
                "failed" -> TelecomPaymentStatus.Failed
                else -> error("Unknown payment status")
            }
        return TelecomPayment(
            id(json, "id"),
            label(json, "requestKey"),
            status,
            quote(json.getJSONObject("quote"), false),
            Instant.parse(string(json, "createdAt")),
            nullableString(json, "submissionStartedAt")?.let(Instant::parse),
        )
    }

    /** Accept only the phone-based services supported by this adapter. */
    private fun service(json: JSONObject): TelecomService =
        when (string(json, "serviceType")) {
            "airtime" -> TelecomService.Airtime
            "data" -> TelecomService.Data
            else -> error("Unsupported service")
        }

    /**
     * Nullable airtime fields and required data descriptions remain bounded public display text.
     */
    private fun nullableLabel(json: JSONObject, key: String): String? =
        nullableString(json, key)?.also {
            require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl))
        }

    /** Preserve the documented integral duration without truncating JSON fractions or strings. */
    private fun validitySeconds(json: JSONObject): Long? {
        val value = json.get("validitySeconds")
        if (value == JSONObject.NULL) {
            return null
        }
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it > 0) }
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
        } catch (failure: TelecomFailure) {
            throw failure
        } catch (failure: ApiRequestFailure) {
            val issue =
                when {
                    failure.kind == ApiFailureKind.Configuration -> TelecomIssue.Configuration
                    failure.kind == ApiFailureKind.Network -> TelecomIssue.Network
                    failure.kind == ApiFailureKind.InvalidResponse -> TelecomIssue.InvalidResponse
                    failure.status == 401 -> TelecomIssue.SignInRequired
                    failure.code == "QUOTE_EXPIRED" -> TelecomIssue.QuoteExpired
                    failure.code == "INSUFFICIENT_FUNDS" -> TelecomIssue.InsufficientFunds
                    failure.code == "WALLET_RESTRICTED" -> TelecomIssue.WalletRestricted
                    failure.code in setOf("PAYMENT_CONFLICT", "IDEMPOTENCY_CONFLICT") ->
                        TelecomIssue.PaymentConflict
                    failure.code == "PRODUCT_UNAVAILABLE" -> TelecomIssue.ProductUnavailable
                    failure.code == "INVALID_PAYMENT" -> TelecomIssue.InvalidAmount
                    failure.status == 404 -> TelecomIssue.NotFound
                    else -> TelecomIssue.Unavailable
                }
            throw TelecomFailure(issue, failure)
        } catch (failure: Exception) {
            throw TelecomFailure(TelecomIssue.InvalidResponse, failure)
        }
    }
}
