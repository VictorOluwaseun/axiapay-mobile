package com.kesofty.axiapay.domain.purchases

/**
 * A complete, committed catalogue for one API/account/currency/service; an empty list is a valid
 * snapshot.
 */
data class PurchaseCatalogue(val products: List<PurchaseProduct>)

/** Own durable catalogue reads and network refreshes; cached terms never authorize a payment. */
interface PurchaseCatalogueRepository {
    /** Return local data without HTTP; null means this scope has never completed a refresh. */
    suspend fun read(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue?

    /**
     * Fetch a complete catalogue, atomically replace changed content, then return the committed
     * database snapshot. Failures preserve the previous complete catalogue and propagate
     * cancellation.
     */
    suspend fun refresh(
        owner: PurchaseOwner,
        accessToken: String,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue
}
