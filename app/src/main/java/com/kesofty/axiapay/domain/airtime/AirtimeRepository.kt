package com.kesofty.axiapay.domain.airtime

/**
 * Main-safe, cancellable first-party airtime transport. No automatic mutation retry or demo
 * fallback.
 */
interface AirtimeRepository {
    /** Normalized origin/prefix must match the API that issued the protected credential. */
    val apiIdentity: String

    /** Read all bounded airtime catalogue pages with the same currency/filter scope. */
    suspend fun products(accessToken: String, currency: String): List<AirtimeProduct>

    /** Obtain owned, exact server pricing without reserving or buying airtime. */
    suspend fun quote(accessToken: String, walletId: String, input: AirtimeQuoteInput): AirtimeQuote

    /** Reload existing review terms; never silently replace an expired quote. */
    suspend fun getQuote(accessToken: String, quoteId: String): AirtimeQuote

    /** Explicit consent uses the original quote/key; an error can leave an accepted payment. */
    suspend fun submit(accessToken: String, request: AirtimeRequest): AirtimePayment

    /** Read-only recovery; null means not observed, not proof that a prior submission failed. */
    suspend fun findByRequestKey(accessToken: String, requestKey: String): AirtimePayment?

    /**
     * Explicitly recover an accepted payment under its existing identity, never a new purchase key.
     */
    suspend fun reconcile(accessToken: String, paymentId: String): AirtimePayment
}

/**
 * Private durable recovery journal. Storage failure blocks submission; account/API scopes remain
 * isolated.
 */
interface AirtimeRequestStore {
    /**
     * Return immutable records for this owner only; corruption fails closed instead of forgetting
     * uncertainty.
     */
    suspend fun read(owner: AirtimeOwner): List<AirtimeRequest>

    /**
     * Atomically preserve this reference and all earlier records before permitting POST. Repeating
     * identical input is idempotent.
     */
    suspend fun save(request: AirtimeRequest)

    /**
     * Mark a correlated final receipt as acknowledged, retaining its reference for receipt lookup.
     */
    suspend fun acknowledge(request: AirtimeRequest)
}
