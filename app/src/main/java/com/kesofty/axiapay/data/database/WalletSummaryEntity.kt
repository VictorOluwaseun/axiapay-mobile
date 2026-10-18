package com.kesofty.axiapay.data.database

import androidx.room.*
import com.kesofty.axiapay.domain.wallet.WalletStatus
import com.kesofty.axiapay.domain.wallet.WalletSummary

/**
 * Exact display-only wallet snapshot, owned by an API/account/currency and excluded from spending
 * decisions.
 */
@Entity(
    tableName = "wallet_summaries",
    primaryKeys = ["api_identity", "account_id", "currency"],
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["api_identity", "account_id"],
                childColumns = ["api_identity", "account_id"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["api_identity", "wallet_id"], unique = true)],
)
data class WalletSummaryEntity(
    @ColumnInfo(name = "api_identity") val apiIdentity: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "wallet_id") val walletId: String,
    val currency: String,
    @ColumnInfo(name = "wallet_status") val walletStatus: String,
    @ColumnInfo(name = "available_minor_units") val availableMinorUnits: String,
    @ColumnInfo(name = "reserved_minor_units") val reservedMinorUnits: String,
    @ColumnInfo(name = "total_minor_units") val totalMinorUnits: String,
    @ColumnInfo(name = "fetched_at_epoch_ms") val fetchedAtEpochMs: Long,
) {
    /** Restore exact integers directly from the committed server snapshot. */
    fun toWallet() =
        WalletSummary(
            walletId,
            currency,
            WalletStatus.valueOf(walletStatus),
            availableMinorUnits.toBigInteger(),
            reservedMinorUnits.toBigInteger(),
            totalMinorUnits.toBigInteger(),
        )

    /** Own the wire-independent mapping of wallet amounts into decimal text storage. */
    companion object {
        /**
         * Bind a received wallet to the authenticated account; the response does not provide
         * ownership.
         */
        fun fromWallet(
            apiIdentity: String,
            accountId: String,
            wallet: WalletSummary,
            fetchedAtEpochMs: Long,
        ) =
            WalletSummaryEntity(
                apiIdentity,
                accountId,
                wallet.id,
                wallet.currency,
                wallet.status.name,
                wallet.availableMinorUnits.toString(),
                wallet.reservedMinorUnits.toString(),
                wallet.totalMinorUnits.toString(),
                fetchedAtEpochMs,
            )
    }
}
