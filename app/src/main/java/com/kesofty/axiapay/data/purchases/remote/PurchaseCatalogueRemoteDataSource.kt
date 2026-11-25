package com.kesofty.axiapay.data.purchases.remote

import com.kesofty.axiapay.domain.purchases.PurchaseProduct
import com.kesofty.axiapay.domain.purchases.PurchaseService

/** Reads the complete remote catalogue for one service and currency from its issuing API. */
interface PurchaseCatalogueRemoteDataSource {
    /** Identifies the API that will receive the session credential. */
    val apiIdentity: String

    /** Read all bounded service-scoped catalogue pages with the same currency/filter scope. */
    suspend fun products(
        accessToken: String,
        currency: String,
        service: PurchaseService = PurchaseService.Airtime,
    ): List<PurchaseProduct>
}
