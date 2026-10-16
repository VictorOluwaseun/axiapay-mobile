package com.kesofty.axiapay.domain.telecom

import com.kesofty.axiapay.domain.wallet.WalletSummary
import java.math.BigInteger
import java.time.Instant

/**
 * Supported phone-based purchases share consent/recovery while retaining distinct catalogue scopes.
 */
enum class TelecomService {
    Airtime,
    Data,
}

/** Account and normalized API scope for private recovery records; never a spending credential. */
data class TelecomOwner(val accountId: String, val apiIdentity: String)

/** First-party catalogue terms. Product identity and network selection come from the API. */
data class TelecomProduct(
    val id: String,
    val displayName: String,
    val networkCode: String,
    val currency: String,
    val fixedMinorUnits: BigInteger?,
    val minimumMinorUnits: BigInteger,
    val maximumMinorUnits: BigInteger,
    val stepMinorUnits: BigInteger,
    val feeMinorUnits: BigInteger,
    val isSubmissionAvailable: Boolean,
    val service: TelecomService = TelecomService.Airtime,
    val allowanceLabel: String? = null,
    val validitySeconds: Long? = null,
)

/** Boundary-prepared recipient and exact amount; fixed-price products omit the amount override. */
data class TelecomQuoteInput(
    val productId: String,
    val recipientPhoneNumber: String,
    val amountMinorUnits: BigInteger?,
    val service: TelecomService = TelecomService.Airtime,
) {
    /** Keep personal and financial form details out of diagnostics. */
    override fun toString() = "TelecomQuoteInput(redacted)"
}

/** Immutable server review. A quote establishes neither a hold nor successful delivery. */
data class TelecomQuote(
    val id: String,
    val walletId: String,
    val productId: String,
    val displayName: String,
    val networkCode: String,
    val recipientPhoneNumber: String,
    val currency: String,
    val principalMinorUnits: BigInteger,
    val feeMinorUnits: BigInteger,
    val totalDebitMinorUnits: BigInteger,
    val createdAt: Instant,
    val expiresAt: Instant,
    val isSubmissionAvailable: Boolean,
    val service: TelecomService = TelecomService.Airtime,
    val allowanceLabel: String? = null,
    val validitySeconds: Long? = null,
) {
    /** Exclude recipient and monetary values from diagnostic interpolation. */
    override fun toString() = "TelecomQuote(redacted)"

    /**
     * Correlate observed progress to the reviewed financial terms, excluding current capability.
     */
    fun hasSameTerms(other: TelecomQuote): Boolean =
        copy(isSubmissionAvailable = false) == other.copy(isSubmissionAvailable = false)
}

/** Observable server state; only committed success or failure is terminal. */
enum class TelecomPaymentStatus {
    AwaitingFunds,
    Reserved,
    Pending,
    Succeeded,
    Failed,
}

/** Authenticated progress. Pending and reserved outcomes must never become successful receipts. */
data class TelecomPayment(
    val id: String,
    val requestKey: String,
    val status: TelecomPaymentStatus,
    val quote: TelecomQuote,
    val createdAt: Instant,
    val submissionStartedAt: Instant?,
) {
    /** Final server outcomes permit acknowledgement of the local recovery reference. */
    val isFinal: Boolean
        get() = status in setOf(TelecomPaymentStatus.Succeeded, TelecomPaymentStatus.Failed)

    /** Avoid exposing private payment details through state logging. */
    override fun toString() = "TelecomPayment(status=$status, details=redacted)"
}

/**
 * Persist before POST: only owner/API scope, original key and quote reference, never credentials or
 * recipient/amount.
 */
data class TelecomRequest(
    val owner: TelecomOwner,
    val requestKey: String,
    val quoteId: String,
    val createdAt: Instant,
    val acknowledged: Boolean = false,
    val service: TelecomService = TelecomService.Airtime,
)

/** Server wallet and catalogue alongside this account's durable recovery references. */
data class TelecomContext(
    val owner: TelecomOwner,
    val wallet: WalletSummary?,
    val products: List<TelecomProduct>,
    val requests: List<TelecomRequest>,
    val issue: TelecomIssue? = null,
)

/** Explicitly reviewed terms retain one logical identity across retries. */
data class TelecomReview(val request: TelecomRequest, val quote: TelecomQuote)

/**
 * A lost response remains unconfirmed with its original identity; an optional quote is
 * display-only.
 */
data class TelecomProgress(
    val request: TelecomRequest,
    val payment: TelecomPayment?,
    val quote: TelecomQuote?,
    val issue: TelecomIssue? = null,
)

/**
 * Safe actionable classifications; server bodies, tokens and provider diagnostics never reach UI.
 */
enum class TelecomIssue {
    SignInRequired,
    SessionChanged,
    Configuration,
    WalletMissing,
    WalletUnavailable,
    Network,
    Unavailable,
    InvalidResponse,
    SelectProduct,
    InvalidRecipient,
    InvalidAmount,
    ProductUnavailable,
    QuoteExpired,
    PaymentConflict,
    InsufficientFunds,
    WalletRestricted,
    Storage,
    NotFound,
}

/** Preserve safe failure context without exposing upstream response text. */
class TelecomFailure(val issue: TelecomIssue, cause: Throwable? = null) :
    Exception("Telecom operation failed: $issue", cause)
