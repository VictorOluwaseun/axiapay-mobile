package com.kesofty.axiapay.domain.telecom

/**
 * Main-safe, cancellable first-party airtime/data transport. No automatic mutation retry or demo
 * fallback.
 */
interface TelecomRepository {
    /** Normalized origin/prefix must match the API that issued the protected credential. */
    val apiIdentity: String

    /** Read all bounded service-scoped catalogue pages with the same currency/filter scope. */
    suspend fun products(
        accessToken: String,
        currency: String,
        service: TelecomService = TelecomService.Airtime,
    ): List<TelecomProduct>

    /** Obtain owned, exact server pricing without reserving or buying telecom. */
    suspend fun quote(accessToken: String, walletId: String, input: TelecomQuoteInput): TelecomQuote

    /** Reload existing review terms; never silently replace an expired quote. */
    suspend fun getQuote(accessToken: String, quoteId: String): TelecomQuote

    /** Explicit consent uses the original quote/key; an error can leave an accepted payment. */
    suspend fun submit(accessToken: String, request: TelecomRequest): TelecomPayment

    /** Read-only recovery; null means not observed, not proof that a prior submission failed. */
    suspend fun findByRequestKey(accessToken: String, requestKey: String): TelecomPayment?

    /**
     * Explicitly recover an accepted payment under its existing identity, never a new purchase key.
     */
    suspend fun reconcile(accessToken: String, paymentId: String): TelecomPayment
}

/**
 * Private durable recovery journal. Storage failure blocks submission; account/API scopes remain
 * isolated.
 */
interface TelecomRequestStore {
    /**
     * Return immutable records for this owner only; corruption fails closed instead of forgetting
     * uncertainty.
     */
    suspend fun read(owner: TelecomOwner): List<TelecomRequest>

    /**
     * Atomically preserve this reference and all earlier records before permitting POST. Repeating
     * identical input is idempotent.
     */
    suspend fun save(request: TelecomRequest)

    /**
     * Mark a correlated final receipt as acknowledged, retaining its reference for receipt lookup.
     */
    suspend fun acknowledge(request: TelecomRequest)
}
