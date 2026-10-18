package com.kesofty.axiapay.domain.config

/** Stable feature identities shared with the public configuration contract. */
enum class PaymentFeature(val configurationKey: String) {
    Airtime("airtime"),
    Data("data"),
    Electricity("electricity"),
    Tv("tv"),
    Education("education"),
    Insurance("insurance"),
    BankTransfer("bankTransfer"),
    Merchant("merchant"),
    AirtimeConversion("airtimeConversion"),
}

/**
 * Immutable advisory availability. Missing policy never enables payments; the API remains
 * authoritative.
 */
data class PaymentFeatures(
    val airtime: Boolean = false,
    val data: Boolean = false,
    val electricity: Boolean = false,
    val tv: Boolean = false,
    val education: Boolean = false,
    val insurance: Boolean = false,
    val bankTransfer: Boolean = false,
    val merchant: Boolean = false,
    val airtimeConversion: Boolean = false,
) {
    fun isEnabled(feature: PaymentFeature): Boolean =
        when (feature) {
            PaymentFeature.Airtime -> airtime
            PaymentFeature.Data -> data
            PaymentFeature.Electricity -> electricity
            PaymentFeature.Tv -> tv
            PaymentFeature.Education -> education
            PaymentFeature.Insurance -> insurance
            PaymentFeature.BankTransfer -> bankTransfer
            PaymentFeature.Merchant -> merchant
            PaymentFeature.AirtimeConversion -> airtimeConversion
        }

    companion object {
        val Disabled = PaymentFeatures()
        val AllEnabled =
            PaymentFeatures(
                airtime = true,
                data = true,
                electricity = true,
                tv = true,
                education = true,
                insurance = true,
                bankTransfer = true,
                merchant = true,
                airtimeConversion = true,
            )
    }
}
