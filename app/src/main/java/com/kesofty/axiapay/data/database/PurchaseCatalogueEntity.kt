package com.kesofty.axiapay.data.database

import androidx.room.*
import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.domain.purchases.*

/**
 * Marks complete downloads, including empty catalogues; removing an account also removes its
 * catalogue.
 */
@Entity(
    tableName = "purchase_catalogues",
    primaryKeys = ["api_identity", "account_id", "currency", "service"],
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["api_identity", "account_id"],
                childColumns = ["api_identity", "account_id"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class PurchaseCatalogueEntity(
    @ColumnInfo(name = "api_identity") val apiIdentity: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val currency: String,
    val service: String,
    @ColumnInfo(name = "changed_at_epoch_ms") val changedAtEpochMs: Long,
)

/**
 * Typed display-only catalogue terms preserve API IDs, exact decimal money and optional plan
 * metadata.
 */
@Entity(
    tableName = "purchase_products",
    primaryKeys = ["api_identity", "account_id", "currency", "service", "product_id"],
    foreignKeys =
        [
            ForeignKey(
                entity = PurchaseCatalogueEntity::class,
                parentColumns = ["api_identity", "account_id", "currency", "service"],
                childColumns = ["api_identity", "account_id", "currency", "service"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class PurchaseProductEntity(
    @ColumnInfo(name = "api_identity") val apiIdentity: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val currency: String,
    val service: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "provider_code") val providerCode: String,
    @ColumnInfo(name = "fixed_minor_units") val fixedMinorUnits: String?,
    @ColumnInfo(name = "minimum_minor_units") val minimumMinorUnits: String,
    @ColumnInfo(name = "maximum_minor_units") val maximumMinorUnits: String,
    @ColumnInfo(name = "step_minor_units") val stepMinorUnits: String,
    @ColumnInfo(name = "fee_minor_units") val feeMinorUnits: String,
    @ColumnInfo(name = "submission_available") val submissionAvailable: Boolean,
    @ColumnInfo(name = "allowance_label") val allowanceLabel: String?,
    @ColumnInfo(name = "validity_seconds") val validitySeconds: Long?,
    @ColumnInfo(name = "meter_type") val meterType: String?,
) {
    /** Restore the exact validated catalogue record without introducing demo defaults. */
    fun toProduct() =
        PurchaseProduct(
            productId,
            displayName,
            providerCode,
            currency,
            fixedMinorUnits?.toBigInteger(),
            minimumMinorUnits.toBigInteger(),
            maximumMinorUnits.toBigInteger(),
            stepMinorUnits.toBigInteger(),
            feeMinorUnits.toBigInteger(),
            submissionAvailable,
            PurchaseService.valueOf(service),
            allowanceLabel,
            validitySeconds,
            meterType?.let(ElectricityMeterType::valueOf),
        )

    /** Keep persistence-only mapping outside the application and domain layers. */
    companion object {
        /** Bind each received product to the verified local owner rather than the bearer token. */
        fun fromProduct(owner: PurchaseOwner, product: PurchaseProduct) =
            PurchaseProductEntity(
                owner.apiIdentity,
                owner.accountId,
                product.currency,
                product.service.name,
                product.id,
                product.displayName,
                product.providerCode,
                product.fixedMinorUnits?.toString(),
                product.minimumMinorUnits.toString(),
                product.maximumMinorUnits.toString(),
                product.stepMinorUnits.toString(),
                product.feeMinorUnits.toString(),
                product.isSubmissionAvailable,
                product.allowanceLabel,
                product.validitySeconds,
                product.meterType?.name,
            )
    }
}
