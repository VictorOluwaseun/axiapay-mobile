package com.kesofty.axiapay.data.purchases

import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.domain.purchases.*
import java.math.BigInteger
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * First-party wallet purchase DTO adapter; bearer headers and bounded uncached IO use the shared
 * transport.
 */
class HttpPurchaseRepository(private val http: ApiHttpClient) : PurchaseRepository {
    override val apiIdentity: String
        get() =
            try {
                http.apiIdentity
            } catch (failure: ApiRequestFailure) {
                throw PurchaseFailure(PurchaseIssue.Configuration, failure)
            }

    /** Follow bounded pages with unchanged filters; never substitute a local product catalogue. */
    override suspend fun products(
        accessToken: String,
        currency: String,
        service: PurchaseService,
    ): List<PurchaseProduct> = mapped {
        val products = mutableListOf<PurchaseProduct>()
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
        throw PurchaseFailure(PurchaseIssue.InvalidResponse)
    }

    /** Verify exact meter text with the selected server product; never send a payment here. */
    override suspend fun verifyMeter(
        accessToken: String,
        productId: String,
        accountNumber: String,
    ): MeterValidation = mapped {
        val body = JSONObject().put("productId", productId).put("accountNumber", accountNumber)
        val result =
            meterValidation(
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
    ): PurchaseQuote = mapped {
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
        quote(requireNotNull(http.request("payments/quotes", 201, body, accessToken)), true)
    }

    /** Retrieve the exact original quote, including capability and expiry, without requoting. */
    override suspend fun getQuote(accessToken: String, quoteId: String): PurchaseQuote = mapped {
        val quote =
            quote(
                requireNotNull(http.request("payments/quotes/$quoteId", 200, bearer = accessToken)),
                true,
            )
        require(quote.id == quoteId)
        quote
    }

    /** A single consent POST contains only the original quote and request key. */
    override suspend fun submit(accessToken: String, request: PurchaseRequest): PurchasePayment =
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
    ): PurchasePayment? {
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
        service: PurchaseService,
    ): PurchaseProduct {
        require(service(json) == service && string(json, "currency") == currency)
        val minimum = amount(json, "minimumAmountMinorUnits")
        val maximum = amount(json, "maximumAmountMinorUnits")
        val step = amount(json, "amountStepMinorUnits")
        val fixed = nullableString(json, "fixedPriceMinorUnits")?.let(::minorUnits)
        require(minimum > BigInteger.ZERO && maximum >= minimum && step > BigInteger.ZERO)
        require(fixed == null || fixed in minimum..maximum)
        val allowance = nullableLabel(json, "allowanceLabel")
        val validity = validitySeconds(json)
        if (service == PurchaseService.Data) {
            require(fixed != null && fixed == minimum && fixed == maximum)
            require(allowance != null && validity != null)
        }
        val meterType = if (service == PurchaseService.Electricity) meterType(json) else null
        if (meterType != null) {
            require(fixed == null && allowance == null && validity == null)
            require(nullableString(json, "networkCode") == null)
        }
        return PurchaseProduct(
            id(json, "id"),
            label(json, "displayName"),
            providerCode(json, service),
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
            meterType,
        )
    }

    /**
     * Read exact immutable review terms; progress quotes deliberately have no current-submission
     * capability.
     */
    private fun quote(json: JSONObject, withCapability: Boolean): PurchaseQuote {
        val service = service(json)
        require(string(json, "currency") == "NGN")
        val principal = amount(json, "principalMinorUnits")
        val fee = amount(json, "feeMinorUnits")
        val total = amount(json, "totalDebitMinorUnits")
        val created = Instant.parse(string(json, "createdAt"))
        val expires = Instant.parse(string(json, "expiresAt"))
        val validation =
            if (service == PurchaseService.Electricity)
                meterValidation(json.getJSONObject("accountValidation"))
            else null
        val recipient = validation?.accountNumber ?: string(json, "recipientPhoneNumber")
        require(principal > BigInteger.ZERO && total == principal + fee && expires > created)
        if (validation == null) {
            require(Regex("\\+[1-9][0-9]{7,14}").matches(recipient))
        }
        val contact = if (validation == null) null else string(json, "contactPhoneNumber")
        if (validation != null) {
            require(validation.productId == id(json, "productId"))
            require(
                validation.billerCode == providerCode(json, service) &&
                    validation.meterType == meterType(json),
            )
            require(
                nullableString(json, "recipientPhoneNumber") == null &&
                    nullableString(json, "networkCode") == null,
            )
            require(expires <= validation.expiresAt && created >= validation.verifiedAt)
            require(Regex("\\+[1-9][0-9]{7,14}").matches(requireNotNull(contact)))
        }
        val allowance = nullableLabel(json, "allowanceLabel")
        val validity = validitySeconds(json)
        if (service == PurchaseService.Data) {
            require(allowance != null && validity != null)
        }
        return PurchaseQuote(
            id(json, "id"),
            id(json, "walletId"),
            id(json, "productId"),
            label(json, "displayName"),
            providerCode(json, service),
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
            validation,
            contact,
        )
    }

    /** Only documented committed status values may become progress or a final receipt. */
    private fun payment(json: JSONObject): PurchasePayment {
        val status =
            when (string(json, "paymentStatus")) {
                "awaiting_funds" -> PurchasePaymentStatus.AwaitingFunds
                "reserved" -> PurchasePaymentStatus.Reserved
                "pending" -> PurchasePaymentStatus.Pending
                "succeeded" -> PurchasePaymentStatus.Succeeded
                "failed" -> PurchasePaymentStatus.Failed
                else -> error("Unknown payment status")
            }
        val quote = quote(json.getJSONObject("quote"), false)
        return PurchasePayment(
            id(json, "id"),
            label(json, "requestKey"),
            status,
            quote,
            Instant.parse(string(json, "createdAt")),
            nullableString(json, "submissionStartedAt")?.let(Instant::parse),
            if (quote.service == PurchaseService.Electricity) receipt(json, quote, status)
            else null,
        )
    }

    /** Accept only implemented service contracts, never a guessed provider mapping. */
    private fun service(json: JSONObject): PurchaseService =
        when (string(json, "serviceType")) {
            "airtime" -> PurchaseService.Airtime
            "data" -> PurchaseService.Data
            "electricity" -> PurchaseService.Electricity
            else -> error("Unsupported service")
        }

    /** Catalogue codes belong to their service and retain canonical server identity. */
    private fun providerCode(json: JSONObject, service: PurchaseService): String {
        if (service != PurchaseService.Electricity) {
            return label(json, "networkCode")
        }
        return string(json, "billerCode").also {
            require(Regex("[a-z0-9][a-z0-9-]{0,49}").matches(it))
        }
    }

    /** Meter mode comes from the selected product and cannot be overridden during payment. */
    private fun meterType(json: JSONObject): ElectricityMeterType =
        when (string(json, "electricityMeterType")) {
            "prepaid" -> ElectricityMeterType.Prepaid
            "postpaid" -> ElectricityMeterType.Postpaid
            else -> error("Unsupported meter mode")
        }

    /**
     * Project the authenticated meter capability without leaking transport models or private
     * diagnostics.
     */
    private fun meterValidation(json: JSONObject): MeterValidation {
        require(service(json) == PurchaseService.Electricity)
        val account = string(json, "accountNumber")
        require(
            account.length in 1..100 &&
                account == account.trim() &&
                account.all { it.code in 32..126 },
        )
        val verifiedAt = Instant.parse(string(json, "verifiedAt"))
        val expiresAt = Instant.parse(string(json, "expiresAt"))
        require(expiresAt > verifiedAt)
        val address = nullableString(json, "serviceAddress")?.also { displayText(it, 500) }
        return MeterValidation(
            id(json, "id"),
            id(json, "productId"),
            providerCode(json, PurchaseService.Electricity),
            meterType(json),
            account,
            label(json, "customerName"),
            address,
            verifiedAt,
            expiresAt,
        )
    }

    /**
     * Separate financial state from typed receipt availability; missing tokens remain recoverable.
     */
    private fun receipt(
        json: JSONObject,
        quote: PurchaseQuote,
        status: PurchasePaymentStatus,
    ): ElectricityReceipt? {
        val fulfilmentStatus = string(json, "fulfilmentStatus")
        val body = json.get("fulfilment")
        require(fulfilmentStatus in setOf("not_available", "pending", "available"))
        if (fulfilmentStatus != "available") {
            require(body == JSONObject.NULL)
            require(status != PurchasePaymentStatus.Succeeded || fulfilmentStatus == "pending")
            return null
        }
        require(body is JSONObject && status != PurchasePaymentStatus.Failed)
        val type = requireNotNull(quote.meterValidation).meterType
        val expected =
            if (type == ElectricityMeterType.Prepaid) "electricity_prepaid"
            else "electricity_postpaid"
        require(string(body, "fulfilmentType") == expected)
        val reference = label(body, "receiptReference")
        if (type == ElectricityMeterType.Postpaid) {
            return ElectricityReceipt(type, reference)
        }
        val rows = body.getJSONArray("tokens")
        require(rows.length() in 1..8)
        val tokens =
            (0 until rows.length()).map { index ->
                val token = rows.getJSONObject(index)
                ElectricityToken(
                    string(token, "label").also { displayText(it, 100) },
                    string(token, "value").also { displayText(it, 128) },
                )
            }
        require(tokens.map { it.value }.distinct().size == tokens.size)
        val units =
            nullableString(body, "unitsKilowattHours")?.also {
                require(Regex("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,9})?").matches(it))
            }
        return ElectricityReceipt(type, reference, Collections.unmodifiableList(tokens), units)
    }

    /** Bounded printable receipt text never permits controls in an authenticated display value. */
    private fun displayText(value: String, maximumLength: Int) {
        require(
            value.isNotBlank() && value.length <= maximumLength && value.none(Char::isISOControl),
        )
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
        } catch (failure: PurchaseFailure) {
            throw failure
        } catch (failure: ApiRequestFailure) {
            val issue =
                when {
                    failure.kind == ApiFailureKind.Configuration -> PurchaseIssue.Configuration
                    failure.kind == ApiFailureKind.Network -> PurchaseIssue.Network
                    failure.kind == ApiFailureKind.InvalidResponse -> PurchaseIssue.InvalidResponse
                    failure.status == 401 -> PurchaseIssue.SignInRequired
                    failure.code == "ACCOUNT_INVALID" -> PurchaseIssue.InvalidMeter
                    failure.code == "ACCOUNT_UNCONFIRMED" -> PurchaseIssue.MeterUnconfirmed
                    failure.code == "ACCOUNT_VALIDATION_EXPIRED" ->
                        PurchaseIssue.MeterVerificationExpired
                    failure.code == "ACCOUNT_VALIDATION_NOT_FOUND" ->
                        PurchaseIssue.MeterVerificationRequired
                    failure.code == "QUOTE_EXPIRED" -> PurchaseIssue.QuoteExpired
                    failure.code == "INSUFFICIENT_FUNDS" -> PurchaseIssue.InsufficientFunds
                    failure.code == "WALLET_RESTRICTED" -> PurchaseIssue.WalletRestricted
                    failure.code in setOf("PAYMENT_CONFLICT", "IDEMPOTENCY_CONFLICT") ->
                        PurchaseIssue.PaymentConflict
                    failure.code == "PRODUCT_UNAVAILABLE" -> PurchaseIssue.ProductUnavailable
                    failure.code == "INVALID_PAYMENT" -> PurchaseIssue.InvalidPayment
                    failure.status == 404 -> PurchaseIssue.NotFound
                    else -> PurchaseIssue.Unavailable
                }
            throw PurchaseFailure(issue, failure)
        } catch (failure: Exception) {
            throw PurchaseFailure(PurchaseIssue.InvalidResponse, failure)
        }
    }
}
