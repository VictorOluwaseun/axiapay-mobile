package com.kesofty.axiapay.data.database

import androidx.room.*

/** Atomic public configuration storage, independent of authentication and account removal. */
@Dao
interface ConfigurationDao {
    /** Load a complete policy only for this API. */
    @Query("SELECT * FROM app_configuration WHERE api_identity = :apiIdentity")
    suspend fun read(apiIdentity: String): AppConfigurationEntity?

    /** Store the complete settings and HTTP validator together. */
    @Upsert suspend fun save(configuration: AppConfigurationEntity)

    /** A 304 refreshes only the matching committed representation's validation time. */
    @Query(
        "UPDATE app_configuration SET checked_at_epoch_ms = :checkedAtEpochMs WHERE api_identity = :apiIdentity AND etag = :etag",
    )
    suspend fun markChecked(apiIdentity: String, etag: String, checkedAtEpochMs: Long): Int
}
