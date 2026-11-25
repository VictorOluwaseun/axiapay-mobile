package com.kesofty.axiapay.data.purchases.remote

import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.domain.purchases.*
import java.util.Collections
import java.util.Locale

/** Downloads all pages before returning a catalogue to the repository for persistence. */
class HttpPurchaseCatalogueRemoteDataSource(private val http: ApiHttpClient) :
    PurchaseCatalogueRemoteDataSource {
    override val apiIdentity: String
        get() = mapPurchaseResponse { http.apiIdentity }

    /** Follow bounded pages with unchanged filters; never substitute a local product catalogue. */
    override suspend fun products(
        accessToken: String,
        currency: String,
        service: PurchaseService,
    ): List<PurchaseProduct> = mapPurchaseResponse {
        val products = mutableListOf<PurchaseProduct>()
        val seenCursors = mutableSetOf<String>()
        val seenProducts = mutableSetOf<String>()
        var cursor: String? = null
        for (page in 0 until 10) {
            val query =
                mutableMapOf(
                    "serviceType" to service.name.lowercase(Locale.ROOT),
                    "currency" to currency,
                    "limit" to "100",
                )
            cursor?.let { query["afterId"] = it }
            val json =
                requireNotNull(
                    http.request(
                        "service-catalog/products",
                        200,
                        bearer = accessToken,
                        query = query,
                    ),
                )
            val rows = json.getJSONArray("products")
            require(rows.length() <= 100)
            for (index in 0 until rows.length()) {
                val product =
                    PurchaseJsonCodec.product(rows.getJSONObject(index), currency, service)
                require(seenProducts.add(product.id))
                products += product
            }
            cursor = PurchaseJsonCodec.nextCursor(json)
            if (cursor == null) {
                return@mapPurchaseResponse Collections.unmodifiableList(products.toList())
            }
            require(seenCursors.add(cursor))
        }
        throw PurchaseFailure(PurchaseIssue.InvalidResponse)
    }
}
