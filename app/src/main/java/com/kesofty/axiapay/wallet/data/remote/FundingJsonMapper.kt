package com.kesofty.axiapay.wallet.data.remote

import com.kesofty.axiapay.wallet.domain.*
import java.math.BigInteger
import java.net.URI
import java.util.Collections
import java.util.UUID
import org.json.JSONObject

/** Map a bounded history page, correlating each returned request with its wallet. */
internal fun JSONObject.toFundingContext(walletId: String): FundingContext {
    val options = getJSONArray("options")
    val requests = getJSONArray("requests")
    require(options.length() <= 100 && requests.length() <= 25)
    return FundingContext(
        walletId,
        Collections.unmodifiableList(
            List(options.length()) { index ->
                val option = options.getJSONObject(index)
                FundingOption(
                    option.requiredText("id"),
                    option.requiredText("displayName"),
                    option.requiredText("currency"),
                    option.minorUnits("minimumAmountMinorUnits"),
                    option.minorUnits("maximumAmountMinorUnits"),
                    option.opt("flow") == "bank_transfer",
                )
            },
        ),
        Collections.unmodifiableList(
            List(requests.length()) { index ->
                requests.getJSONObject(index).toFundingRequest().also {
                    require(it.walletId == walletId)
                }
            },
        ),
        opt("nextOffset") as? Int,
    )
}

/**
 * Decode only documented statuses and HTTPS hosted checkout; unknown responses never imply success.
 */
internal fun JSONObject.toFundingRequest(): FundingRequest {
    val id = requiredText("fundingId")
    val walletId = requiredText("walletId")
    require(
        UUID.fromString(id).toString() == id && UUID.fromString(walletId).toString() == walletId,
    )
    val status =
        when (requiredText("fundingStatus")) {
            "pending" -> FundingStatus.Pending
            "awaiting_transfer" -> FundingStatus.AwaitingTransfer
            "awaiting_confirmation" -> FundingStatus.AwaitingConfirmation
            "awaiting_credit" -> FundingStatus.AwaitingCredit
            "rejected" -> FundingStatus.Rejected
            "credited" -> FundingStatus.Credited
            else -> error("Unknown funding status")
        }
    val url = optionalText("checkoutUrl")
    url?.let {
        val checkout = URI(it)
        require(checkout.scheme == "https" && checkout.host != null && checkout.userInfo == null)
    }
    val account =
        optJSONObject("transferInstructions")?.let {
            FundingBankAccount(
                it.requiredText("bankName"),
                it.requiredText("accountName"),
                it.requiredText("accountNumber"),
            )
        }
    require(requiredText("currency") == "NGN")
    require(status != FundingStatus.Credited || optionalText("operationId") != null)
    return FundingRequest(
        id,
        walletId,
        requiredText("fundingOptionId"),
        minorUnits("amountMinorUnits"),
        "NGN",
        status,
        account,
        url,
        optionalText("customerReference"),
        optionalText("reviewNote"),
    )
}

/** Reject implicit JSON coercion at this external boundary. */
private fun JSONObject.requiredText(name: String) = get(name) as? String ?: error("Expected string")

/** Allow missing/null fields without accepting wrong primitive types. */
private fun JSONObject.optionalText(name: String): String? =
    if (!has(name) || isNull(name)) null else requiredText(name)

/** Parse exact canonical minor units without floating point arithmetic. */
private fun JSONObject.minorUnits(name: String): BigInteger {
    val value = requiredText(name)
    require(Regex("[1-9][0-9]{0,18}").matches(value))
    return value.toBigInteger()
}
