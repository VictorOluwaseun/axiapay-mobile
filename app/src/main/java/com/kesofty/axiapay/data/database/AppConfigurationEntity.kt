package com.kesofty.axiapay.data.database

import androidx.room.*
import com.kesofty.axiapay.domain.config.*

/** One complete public policy and its quoted HTTP validator per API, committed as one row. */
@Entity(tableName = "app_configuration")
data class AppConfigurationEntity(
    @PrimaryKey @ColumnInfo(name = "api_identity") val apiIdentity: String,
    val version: String,
    val etag: String,
    @ColumnInfo(name = "signup_email_enabled") val signupEmailEnabled: Boolean,
    @ColumnInfo(name = "signup_phone_enabled") val signupPhoneEnabled: Boolean,
    @ColumnInfo(name = "login_email_enabled") val loginEmailEnabled: Boolean,
    @ColumnInfo(name = "login_phone_enabled") val loginPhoneEnabled: Boolean,
    @ColumnInfo(name = "signup_password_enabled") val signupPasswordEnabled: Boolean,
    @ColumnInfo(name = "signup_otp_enabled") val signupOtpEnabled: Boolean,
    @ColumnInfo(name = "login_password_enabled") val loginPasswordEnabled: Boolean,
    @ColumnInfo(name = "login_otp_enabled") val loginOtpEnabled: Boolean,
    @ColumnInfo(name = "airtime_enabled") val airtimeEnabled: Boolean,
    @ColumnInfo(name = "data_enabled") val dataEnabled: Boolean,
    @ColumnInfo(name = "tv_enabled") val tvEnabled: Boolean,
    @ColumnInfo(name = "electricity_enabled") val electricityEnabled: Boolean,
    @ColumnInfo(name = "education_enabled") val educationEnabled: Boolean,
    @ColumnInfo(name = "insurance_enabled") val insuranceEnabled: Boolean,
    @ColumnInfo(name = "bank_transfer_enabled") val bankTransferEnabled: Boolean,
    @ColumnInfo(name = "merchant_enabled") val merchantEnabled: Boolean,
    @ColumnInfo(name = "airtime_conversion_enabled") val airtimeConversionEnabled: Boolean,
    @ColumnInfo(name = "checked_at_epoch_ms") val checkedAtEpochMs: Long,
) {
    /** Reconstruct the feature contract without JSON parsing or duplicate validation. */
    fun toConfiguration() =
        RemoteConfiguration(
            version,
            AuthenticationMethods(
                signupEmailEnabled,
                signupPhoneEnabled,
                signupPasswordEnabled,
                signupOtpEnabled,
            ),
            AuthenticationMethods(
                loginEmailEnabled,
                loginPhoneEnabled,
                loginPasswordEnabled,
                loginOtpEnabled,
            ),
            PaymentFeatures(
                airtime = airtimeEnabled,
                data = dataEnabled,
                tv = tvEnabled,
                electricity = electricityEnabled,
                education = educationEnabled,
                insurance = insuranceEnabled,
                bankTransfer = bankTransferEnabled,
                merchant = merchantEnabled,
                airtimeConversion = airtimeConversionEnabled,
            ),
        )

    /** Keep flattened database columns private to the persistence boundary. */
    companion object {
        /** Preserve all received settings, their exact ETag and the successful validation time. */
        fun fromConfiguration(
            apiIdentity: String,
            configuration: RemoteConfiguration,
            etag: String,
            checkedAtEpochMs: Long,
        ) =
            AppConfigurationEntity(
                apiIdentity,
                configuration.version,
                etag,
                configuration.signup.emailEnabled,
                configuration.signup.phoneEnabled,
                configuration.login.emailEnabled,
                configuration.login.phoneEnabled,
                configuration.signup.passwordEnabled,
                configuration.signup.otpEnabled,
                configuration.login.passwordEnabled,
                configuration.login.otpEnabled,
                configuration.features.airtime,
                configuration.features.data,
                configuration.features.tv,
                configuration.features.electricity,
                configuration.features.education,
                configuration.features.insurance,
                configuration.features.bankTransfer,
                configuration.features.merchant,
                configuration.features.airtimeConversion,
                checkedAtEpochMs,
            )
    }
}
