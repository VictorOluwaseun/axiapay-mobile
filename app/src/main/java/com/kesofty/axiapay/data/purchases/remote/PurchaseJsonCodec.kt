package com.kesofty.axiapay.data.purchases.remote

import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.domain.purchases.*
import java.math.BigInteger
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** Decodes the exact catalogue, quote and payment terms returned by the purchase API. */
internal object PurchaseJsonCodec {
    /**
     * A null cursor ends a complete catalogue. Every subsequent page requires a valid product ID.
     */
    fun nextCursor(json: JSONObject): String? =
        nullableString(json, "nextCursor")?.also { require(validId(it)) }

    /**
     * Map public terms, requiring fixed data pricing and plan metadata within the requested scope.
     */
    fun product(
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
    fun quote(json: JSONObject, withCapability: Boolean): PurchaseQuote {
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
    fun payment(json: JSONObject): PurchasePayment {
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
    fun meterValidation(json: JSONObject): MeterValidation {
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
}
