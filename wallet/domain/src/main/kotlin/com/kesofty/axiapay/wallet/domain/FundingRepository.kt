package com.kesofty.axiapay.wallet.domain

import java.math.BigInteger

/** Customer-visible funding capability; the server owns routing and exact amount limits. */
data class FundingOption(
    val id: String,
    val name: String,
    val currency: String,
    val minimum: BigInteger,
    val maximum: BigInteger,
    val bankTransfer: Boolean,
)

/** Saved transfer destination; client launches and copied details never confirm a deposit. */
data class FundingBankAccount(
    val bankName: String,
    val accountName: String,
    val accountNumber: String,
)

/** Authoritative funding progress. Only Credited indicates that the wallet journal committed. */
enum class FundingStatus {
    Pending,
    AwaitingTransfer,
    AwaitingConfirmation,
    AwaitingCredit,
    Rejected,
    Credited,
}

/** Durable server request, owned by the authenticated wallet; evidence remains on the API. */
data class FundingRequest(
    val id: String,
    val walletId: String,
    val optionId: String,
    val amountMinorUnits: BigInteger,
    val currency: String,
    val status: FundingStatus,
    val account: FundingBankAccount?,
    val checkoutUrl: String?,
    val customerReference: String?,
    val reviewNote: String?,
)

/** Bounded recovery page and currently enabled capabilities for one owned wallet. */
data class FundingContext(
    val walletId: String,
    val options: List<FundingOption>,
    val requests: List<FundingRequest>,
    val nextOffset: Int?,
)

/**
 * Prepared exact request terms reused after uncertainty; no provider or server authority is encoded
 * here.
 */
data class FundingInput(
    val optionId: String,
    val amountMinorUnits: BigInteger,
    val payerEmail: String?,
    val requestKey: String,
)

/** Main-safe authenticated funding transport; no local or demo balance fallback is allowed. */
interface FundingRepository {
    val apiIdentity: String

    /** Read saved server requests and available options without initiating a transfer. */
    suspend fun context(token: String, walletId: String, offset: Int): FundingContext

    /** Create the request once per logical key; a timeout retains that key. */
    suspend fun create(token: String, walletId: String, input: FundingInput): FundingRequest

    /** Read saved progress after recreation or an app handoff. */
    suspend fun get(token: String, requestId: String): FundingRequest

    /** Save a matching aid; the operator must independently verify received money. */
    suspend fun submitReference(token: String, requestId: String, reference: String): FundingRequest

    /**
     * Explicitly request server reconciliation; pending review never becomes customer-authorized
     * credit.
     */
    suspend fun reconcile(token: String, requestId: String): FundingRequest
}

/**
 * Safe classifications for presentation; transport bodies and credentials never leave data
 * adapters.
 */
enum class FundingIssue {
    Unavailable,
    Network,
    SignInRequired,
    SessionChanged,
    InvalidInput,
    Conflict,
    InvalidResponse,
}

/** Preserve a bounded failure category, without sensitive request/response diagnostics. */
class FundingFailure(val issue: FundingIssue) : Exception("Funding unavailable: $issue")
