package com.kesofty.axiapay.domain.purchases

/**
 * Main-safe, cancellable first-party airtime/data transport. No automatic mutation retry or demo
 * fallback.
 */
interface PurchaseRepository {
    /** Normalized origin/prefix must match the API that issued the protected credential. */
    val apiIdentity: String

    /** Read all bounded service-scoped catalogue pages with the same currency/filter scope. */
    suspend fun products(
        accessToken: String,
        currency: String,
        service: PurchaseService = PurchaseService.Airtime,
    ): List<PurchaseProduct>

    /** Obtain owned, exact server pricing without reserving or buying telecom. */
    suspend fun quote(
        accessToken: String,
        walletId: String,
        input: PurchaseQuoteInput,
    ): PurchaseQuote

    /** Reload existing review terms; never silently replace an expired quote. */
    suspend fun getQuote(accessToken: String, quoteId: String): PurchaseQuote

    /** Explicit consent uses the original quote/key; an error can leave an accepted payment. */
    suspend fun submit(accessToken: String, request: PurchaseRequest): PurchasePayment

    /** Read-only recovery; null means not observed, not proof that a prior submission failed. */
    suspend fun findByRequestKey(accessToken: String, requestKey: String): PurchasePayment?

    /**
     * Explicitly recover an accepted payment under its existing identity, never a new purchase key.
     */
    suspend fun reconcile(accessToken: String, paymentId: String): PurchasePayment
}

/**
 * Private durable recovery journal. Storage failure blocks submission; account/API scopes remain
 * isolated.
 */
interface PurchaseRequestStore {
    /**
     * Return immutable records for this owner only; corruption fails closed instead of forgetting
     * uncertainty.
     */
    suspend fun read(owner: PurchaseOwner): List<PurchaseRequest>

    /**
     * Atomically preserve this reference and all earlier records before permitting POST. Repeating
     * identical input is idempotent.
     */
    suspend fun save(request: PurchaseRequest)

    /**
     * Mark a correlated final receipt as acknowledged, retaining its reference for receipt lookup.
     */
    suspend fun acknowledge(request: PurchaseRequest)
}
