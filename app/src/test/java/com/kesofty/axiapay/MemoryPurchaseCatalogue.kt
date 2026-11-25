package com.kesofty.axiapay

import com.kesofty.axiapay.data.purchases.remote.PurchaseCatalogueRemoteDataSource
import com.kesofty.axiapay.domain.purchases.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Isolated catalogue persistence for workflow tests; real Room atomicity is verified on the phone.
 */
internal class MemoryPurchaseCatalogue(private val remote: PurchaseCatalogueRemoteDataSource) :
    PurchaseCatalogueRepository {
    private val snapshots =
        mutableMapOf<Triple<PurchaseOwner, String, PurchaseService>, PurchaseCatalogue>()

    /** Read only the requested owner/currency/service. */
    override suspend fun read(owner: PurchaseOwner, currency: String, service: PurchaseService) =
        snapshots[Triple(owner, currency, service)]

    /**
     * Commit complete remote results after cancellation checking, preserving old data on failure.
     */
    override suspend fun refresh(
        owner: PurchaseOwner,
        accessToken: String,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue {
        val products = remote.products(accessToken, currency, service).toList()
        currentCoroutineContext().ensureActive()
        val snapshot = PurchaseCatalogue(products)
        snapshots[Triple(owner, currency, service)] = snapshot
        return snapshot
    }

    /** Seed an explicitly scoped prior catalogue without making any remote call. */
    fun seed(owner: PurchaseOwner, service: PurchaseService, products: List<PurchaseProduct>) {
        snapshots[Triple(owner, "NGN", service)] = PurchaseCatalogue(products.toList())
    }
}
