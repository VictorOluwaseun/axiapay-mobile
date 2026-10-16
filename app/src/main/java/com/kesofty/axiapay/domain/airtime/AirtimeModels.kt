package com.kesofty.axiapay.domain.airtime

import com.kesofty.axiapay.domain.wallet.WalletSummary
import java.math.BigInteger
import java.time.Instant

/** Account and normalized API scope for private recovery records; never a spending credential. */
data class AirtimeOwner(val accountId: String, val apiIdentity: String)

/** First-party catalogue terms. Product identity and network selection come from the API. */
data class AirtimeProduct(
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
)

/** Boundary-prepared recipient and exact amount; fixed-price products omit the amount override. */
data class AirtimeQuoteInput(
    val productId: String,
    val recipientPhoneNumber: String,
    val amountMinorUnits: BigInteger?,
) {
    /** Keep personal and financial form details out of diagnostics. */
    override fun toString() = "AirtimeQuoteInput(redacted)"
}

/** Immutable server review. A quote establishes neither a hold nor successful delivery. */
data class AirtimeQuote(
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
) {
    /** Exclude recipient and monetary values from diagnostic interpolation. */
    override fun toString() = "AirtimeQuote(redacted)"

    /**
     * Correlate observed progress to the reviewed financial terms, excluding current capability.
     */
    fun hasSameTerms(other: AirtimeQuote): Boolean =
        copy(isSubmissionAvailable = false) == other.copy(isSubmissionAvailable = false)
}

/** Observable server state; only committed success or failure is terminal. */
enum class AirtimePaymentStatus {
    AwaitingFunds,
    Reserved,
    Pending,
    Succeeded,
    Failed,
}

/** Authenticated progress. Pending and reserved outcomes must never become successful receipts. */
data class AirtimePayment(
    val id: String,
    val requestKey: String,
    val status: AirtimePaymentStatus,
    val quote: AirtimeQuote,
    val createdAt: Instant,
    val submissionStartedAt: Instant?,
) {
    /** Final server outcomes permit acknowledgement of the local recovery reference. */
    val isFinal: Boolean
        get() = status in setOf(AirtimePaymentStatus.Succeeded, AirtimePaymentStatus.Failed)

    /** Avoid exposing private payment details through state logging. */
    override fun toString() = "AirtimePayment(status=$status, details=redacted)"
}

/**
 * Persist before POST: only owner/API scope, original key and quote reference, never credentials or
 * recipient/amount.
 */
data class AirtimeRequest(
    val owner: AirtimeOwner,
    val requestKey: String,
    val quoteId: String,
    val createdAt: Instant,
    val acknowledged: Boolean = false,
)

/** Server wallet and catalogue alongside this account's durable recovery references. */
data class AirtimeContext(
    val owner: AirtimeOwner,
    val wallet: WalletSummary?,
    val products: List<AirtimeProduct>,
    val requests: List<AirtimeRequest>,
    val issue: AirtimeIssue? = null,
)

/** Explicitly reviewed terms retain one logical identity across retries. */
data class AirtimeReview(val request: AirtimeRequest, val quote: AirtimeQuote)

/**
 * A lost response remains unconfirmed with its original identity; an optional quote is
 * display-only.
 */
data class AirtimeProgress(
    val request: AirtimeRequest,
    val payment: AirtimePayment?,
    val quote: AirtimeQuote?,
    val issue: AirtimeIssue? = null,
)

/**
 * Safe actionable classifications; server bodies, tokens and provider diagnostics never reach UI.
 */
enum class AirtimeIssue {
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
class AirtimeFailure(val issue: AirtimeIssue, cause: Throwable? = null) :
    Exception("Airtime operation failed: $issue", cause)
