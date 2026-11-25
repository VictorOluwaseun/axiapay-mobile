package com.kesofty.axiapay.data.database

import androidx.room.*

/**
 * Scope every catalogue query to the API, account, currency and service; repositories own atomic
 * replacement.
 */
@Dao
interface PurchaseCatalogueDao {
    /** Distinguish an empty complete snapshot from a first-use cache miss. */
    @Query(
        "SELECT * FROM purchase_catalogues WHERE api_identity = :apiIdentity AND account_id = :accountId AND currency = :currency AND service = :service",
    )
    suspend fun catalogue(
        apiIdentity: String,
        accountId: String,
        currency: String,
        service: String,
    ): PurchaseCatalogueEntity?

    /** Deterministic ordering makes change detection independent of page arrival order. */
    @Query(
        "SELECT * FROM purchase_products WHERE api_identity = :apiIdentity AND account_id = :accountId AND currency = :currency AND service = :service ORDER BY product_id",
    )
    suspend fun products(
        apiIdentity: String,
        accountId: String,
        currency: String,
        service: String,
    ): List<PurchaseProductEntity>

    /** Update the complete-snapshot marker without replacing its foreign-key parent. */
    @Upsert suspend fun saveCatalogue(catalogue: PurchaseCatalogueEntity)

    /** Save only rows whose published values changed. */
    @Upsert suspend fun saveProducts(products: List<PurchaseProductEntity>)

    /** Remove withdrawn records within the same replacement transaction. */
    @Query(
        "DELETE FROM purchase_products WHERE api_identity = :apiIdentity AND account_id = :accountId AND currency = :currency AND service = :service AND product_id IN (:withdrawnIds)",
    )
    suspend fun removeWithdrawn(
        apiIdentity: String,
        accountId: String,
        currency: String,
        service: String,
        withdrawnIds: List<String>,
    )
}
