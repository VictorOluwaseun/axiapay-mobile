package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.domain.config.PaymentFeature
import com.kesofty.axiapay.domain.config.PaymentFeatures
import com.kesofty.axiapay.domain.purchases.PurchaseService

/** Map established display routes at the presentation boundary, never in domain configuration. */
fun PaymentFeatures.allowsRoute(route: String): Boolean {
    val feature =
        when (route) {
            "Airtime" -> PaymentFeature.Airtime
            "Data" -> PaymentFeature.Data
            "Electricity" -> PaymentFeature.Electricity
            "TV" -> PaymentFeature.Tv
            "Education" -> PaymentFeature.Education
            "Insurance" -> PaymentFeature.Insurance
            "Bank transfer" -> PaymentFeature.BankTransfer
            "Merchants" -> PaymentFeature.Merchant
            "Convert airtime" -> PaymentFeature.AirtimeConversion
            else -> return true
        }
    return isEnabled(feature)
}

/** Live mobile purchases support three of the independently configured API services. */
fun PaymentFeatures.allows(service: PurchaseService): Boolean =
    isEnabled(
        when (service) {
            PurchaseService.Airtime -> PaymentFeature.Airtime
            PurchaseService.Data -> PaymentFeature.Data
            PurchaseService.Electricity -> PaymentFeature.Electricity
        },
    )
