package com.kesofty.axiapay.data.database

import androidx.room.*

/**
 * Durable dashboard and complete purchase catalogue snapshots; credentials and payment execution
 * records remain separate.
 */
@Database(
    entities =
        [
            AccountEntity::class,
            WalletSummaryEntity::class,
            AppConfigurationEntity::class,
            PurchaseCatalogueEntity::class,
            PurchaseProductEntity::class,
        ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class AxiaDatabase : RoomDatabase() {
    /** Scoped account and wallet persistence. */
    abstract fun dashboard(): DashboardDao

    /** Complete catalogue snapshots, scoped to their authenticated owner. */
    abstract fun purchaseCatalogues(): PurchaseCatalogueDao

    /** Atomic public configuration persistence. */
    abstract fun configuration(): ConfigurationDao
}
