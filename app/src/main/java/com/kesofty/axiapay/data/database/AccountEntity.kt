package com.kesofty.axiapay.data.database

import androidx.room.*
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.AccountContact
import com.kesofty.axiapay.domain.auth.AccountIdentity
import java.time.Instant

/** Non-credential identity received from one API; timestamps record local receipt separately. */
@Entity(tableName = "accounts", primaryKeys = ["api_identity", "account_id"])
data class AccountEntity(
    @ColumnInfo(name = "api_identity") val apiIdentity: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_kind") val contactKind: String,
    @ColumnInfo(name = "contact_value") val contactValue: String,
    @ColumnInfo(name = "verified_at_epoch_ms") val verifiedAtEpochMs: Long,
    @ColumnInfo(name = "fetched_at_epoch_ms") val fetchedAtEpochMs: Long,
) {
    /** Map trusted stored fields without exposing Room models outside the data layer. */
    fun toAccount() =
        AccountIdentity(
            accountId,
            AccountContact(
                when (contactKind) {
                    "email" -> AuthContactType.Email
                    "phone" -> AuthContactType.Phone
                    else -> error("Unsupported stored contact kind")
                },
                contactValue,
            ),
            Instant.ofEpochMilli(verifiedAtEpochMs),
        )

    /** Convert a validated API identity once at the persistence boundary. */
    companion object {
        /** Retain the public identity and the time this device successfully received it. */
        fun fromAccount(apiIdentity: String, account: AccountIdentity, fetchedAtEpochMs: Long) =
            AccountEntity(
                apiIdentity,
                account.id,
                account.contact.kind.name.lowercase(),
                account.contact.value,
                account.verifiedAt.toEpochMilli(),
                fetchedAtEpochMs,
            )
    }
}
