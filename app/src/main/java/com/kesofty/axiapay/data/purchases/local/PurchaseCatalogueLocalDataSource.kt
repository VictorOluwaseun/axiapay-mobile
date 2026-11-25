package com.kesofty.axiapay.data.purchases.local

import com.kesofty.axiapay.domain.purchases.*

/** Stores complete display catalogues by API, account, currency and service. */
interface PurchaseCatalogueLocalDataSource {
    /** Null means no successful download has been saved for this scope. */
    suspend fun read(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue?

    /** Check ownership inside the write transaction and return the newly committed snapshot. */
    suspend fun replace(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
        products: List<PurchaseProduct>,
        requireCurrentSession: suspend () -> Unit,
    ): PurchaseCatalogue
}
