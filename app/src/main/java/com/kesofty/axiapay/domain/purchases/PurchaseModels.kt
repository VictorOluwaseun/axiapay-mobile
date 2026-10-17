package com.kesofty.axiapay.domain.purchases

import com.kesofty.axiapay.domain.wallet.WalletSummary
import java.math.BigInteger
import java.time.Instant

/**
 * Supported phone-based purchases share consent/recovery while retaining distinct catalogue scopes.
 */
enum class PurchaseService {
    Airtime,
    Data,
}

/** Account and normalized API scope for private recovery records; never a spending credential. */
data class PurchaseOwner(val accountId: String, val apiIdentity: String)

/** First-party catalogue terms. Product identity and network selection come from the API. */
data class PurchaseProduct(
    val id: String,
    val displayName: String,
    val providerCode: String,
    val currency: String,
    val fixedMinorUnits: BigInteger?,
    val minimumMinorUnits: BigInteger,
    val maximumMinorUnits: BigInteger,
    val stepMinorUnits: BigInteger,
    val feeMinorUnits: BigInteger,
    val isSubmissionAvailable: Boolean,
    val service: PurchaseService = PurchaseService.Airtime,
    val allowanceLabel: String? = null,
    val validitySeconds: Long? = null,
)

/** Boundary-prepared recipient and exact amount; fixed-price products omit the amount override. */
data class PurchaseQuoteInput(
    val productId: String,
    val recipientIdentifier: String,
    val amountMinorUnits: BigInteger?,
    val service: PurchaseService = PurchaseService.Airtime,
) {
    /** Keep personal and financial form details out of diagnostics. */
    override fun toString() = "PurchaseQuoteInput(redacted)"
}

/** Immutable server review. A quote establishes neither a hold nor successful delivery. */
data class PurchaseQuote(
    val id: String,
    val walletId: String,
    val productId: String,
    val displayName: String,
    val providerCode: String,
    val recipientIdentifier: String,
    val currency: String,
    val principalMinorUnits: BigInteger,
    val feeMinorUnits: BigInteger,
    val totalDebitMinorUnits: BigInteger,
    val createdAt: Instant,
    val expiresAt: Instant,
    val isSubmissionAvailable: Boolean,
    val service: PurchaseService = PurchaseService.Airtime,
    val allowanceLabel: String? = null,
    val validitySeconds: Long? = null,
) {
    /** Exclude recipient and monetary values from diagnostic interpolation. */
    override fun toString() = "PurchaseQuote(redacted)"

    /**
     * Correlate observed progress to the reviewed financial terms, excluding current capability.
     */
    fun hasSameTerms(other: PurchaseQuote): Boolean =
        copy(isSubmissionAvailable = false) == other.copy(isSubmissionAvailable = false)
}

/** Observable server state; only committed success or failure is terminal. */
enum class PurchasePaymentStatus {
    AwaitingFunds,
    Reserved,
    Pending,
    Succeeded,
    Failed,
}

/** Authenticated progress. Pending and reserved outcomes must never become successful receipts. */
data class PurchasePayment(
    val id: String,
    val requestKey: String,
    val status: PurchasePaymentStatus,
    val quote: PurchaseQuote,
    val createdAt: Instant,
    val submissionStartedAt: Instant?,
) {
    /** Final server outcomes permit acknowledgement of the local recovery reference. */
    val isFinal: Boolean
        get() = status in setOf(PurchasePaymentStatus.Succeeded, PurchasePaymentStatus.Failed)

    /** Avoid exposing private payment details through state logging. */
    override fun toString() = "PurchasePayment(status=$status, details=redacted)"
}

/**
 * Persist before POST: only owner/API scope, original key and quote reference, never credentials or
 * recipient/amount.
 */
data class PurchaseRequest(
    val owner: PurchaseOwner,
    val requestKey: String,
    val quoteId: String,
    val createdAt: Instant,
    val acknowledged: Boolean = false,
    val service: PurchaseService = PurchaseService.Airtime,
)

/** Server wallet and catalogue alongside this account's durable recovery references. */
data class PurchaseContext(
    val owner: PurchaseOwner,
    val wallet: WalletSummary?,
    val products: List<PurchaseProduct>,
    val requests: List<PurchaseRequest>,
    val issue: PurchaseIssue? = null,
)

/** Explicitly reviewed terms retain one logical identity across retries. */
data class PurchaseReview(val request: PurchaseRequest, val quote: PurchaseQuote)

/**
 * A lost response remains unconfirmed with its original identity; an optional quote is
 * display-only.
 */
data class PurchaseProgress(
    val request: PurchaseRequest,
    val payment: PurchasePayment?,
    val quote: PurchaseQuote?,
    val issue: PurchaseIssue? = null,
)

/**
 * Safe actionable classifications; server bodies, tokens and provider diagnostics never reach UI.
 */
enum class PurchaseIssue {
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
class PurchaseFailure(val issue: PurchaseIssue, cause: Throwable? = null) :
    Exception("Purchase operation failed: $issue", cause)
