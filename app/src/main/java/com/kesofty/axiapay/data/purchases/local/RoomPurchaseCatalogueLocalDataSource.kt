package com.kesofty.axiapay.data.purchases.local

import androidx.room.withTransaction
import com.kesofty.axiapay.data.database.*
import com.kesofty.axiapay.domain.purchases.*
import java.time.Clock
import java.util.Collections

/** Maintains complete catalogue snapshots and their product rows in a single Room transaction. */
class RoomPurchaseCatalogueLocalDataSource(
    private val database: AxiaDatabase,
    private val clock: Clock,
) : PurchaseCatalogueLocalDataSource {
    /**
     * Read a complete committed snapshot without contacting authentication, wallet or catalogue
     * APIs.
     */
    override suspend fun read(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue? = database.withTransaction { readCommitted(owner, currency, service) }

    /** Replace changed products and remove withdrawn IDs only after the download completes. */
    override suspend fun replace(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
        products: List<PurchaseProduct>,
        requireCurrentSession: suspend () -> Unit,
    ): PurchaseCatalogue {
        val received =
            products.map { PurchaseProductEntity.fromProduct(owner, it) }.sortedBy { it.productId }
        return database.withTransaction {
            requireCurrentSession()
            val dao = database.purchaseCatalogues()
            val previous = dao.products(owner.apiIdentity, owner.accountId, currency, service.name)
            val marker = dao.catalogue(owner.apiIdentity, owner.accountId, currency, service.name)
            if (marker == null || previous != received) {
                replaceChanged(owner, currency, service, previous, received)
            }
            requireNotNull(readCommitted(owner, currency, service))
        }
    }

    /**
     * Upsert changed rows and withdraw missing IDs in one transaction before any new snapshot is
     * published.
     */
    private suspend fun replaceChanged(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
        previous: List<PurchaseProductEntity>,
        received: List<PurchaseProductEntity>,
    ) {
        val dao = database.purchaseCatalogues()
        dao.saveCatalogue(
            PurchaseCatalogueEntity(
                owner.apiIdentity,
                owner.accountId,
                currency,
                service.name,
                clock.millis(),
            ),
        )
        val existing = previous.associateBy { it.productId }
        dao.saveProducts(received.filter { existing[it.productId] != it })
        val retained = received.map { it.productId }.toSet()
        previous
            .map { it.productId }
            .filter { it !in retained }
            .chunked(900)
            .forEach { ids ->
                dao.removeWithdrawn(owner.apiIdentity, owner.accountId, currency, service.name, ids)
            }
    }

    /** Read inside the caller's transaction and detach mutable backing collections. */
    private suspend fun readCommitted(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue? {
        val dao = database.purchaseCatalogues()
        dao.catalogue(owner.apiIdentity, owner.accountId, currency, service.name) ?: return null
        return PurchaseCatalogue(
            Collections.unmodifiableList(
                dao.products(owner.apiIdentity, owner.accountId, currency, service.name).map {
                    it.toProduct()
                },
            ),
        )
    }
}
