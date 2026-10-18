package com.kesofty.axiapay.data.database

import androidx.room.*

/** Durable dashboard snapshots only; credentials and payment execution records remain separate. */
@Database(
    entities = [AccountEntity::class, WalletSummaryEntity::class, AppConfigurationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AxiaDatabase : RoomDatabase() {
    /** Scoped account and wallet persistence. */
    abstract fun dashboard(): DashboardDao

    /** Atomic public configuration persistence. */
    abstract fun configuration(): ConfigurationDao
}
