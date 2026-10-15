package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.BulkService
import com.kesofty.axiapay.domain.ElectricityMeterType

/** Illustrative options only; eligibility and live quotes will come from backend contracts. */
object BulkServiceCatalog {
    /** Reuses the established service validator without importing presentation or data adapters. */
    fun paymentType(service: BulkService): PaymentType =
        when (service) {
            BulkService.Airtime -> PaymentType.Airtime
            BulkService.Data -> PaymentType.Data
            BulkService.Electricity -> PaymentType.Electricity
            BulkService.TV -> PaymentType.TV
        }

    /** Exposes the same sample providers used by the individual payment forms. */
    fun providers(service: BulkService): List<String> = DemoCatalog.providers(paymentType(service))

    /** Package and account-type labels are demo catalog values, not live provider offerings. */
    fun products(service: BulkService): List<String> =
        when (service) {
            BulkService.Airtime -> emptyList()
            BulkService.Data -> listOf("2 GB", "8 GB", "20 GB")
            BulkService.Electricity -> ElectricityMeterType.entries.map { it.name }
            BulkService.TV -> listOf("Basic preview", "Plus preview")
        }

    /**
     * Supplies exact naira strings for fixed-price sample products; variable amounts return null.
     */
    fun quote(service: BulkService, product: String): String? =
        when (service) {
            BulkService.Data -> DemoCatalog.dataAmount(product)
            BulkService.TV ->
                when (product) {
                    "Basic preview" -> "5000"
                    "Plus preview" -> "10000"
                    else -> null
                }
            else -> null
        }

    /**
     * Four editable examples for one service keep demo entry quick without mixing payment types.
     */
    fun sampleRecipients(service: BulkService, newId: () -> String): List<BulkRecipientInput> {
        val providers = providers(service)
        return List(4) { index ->
            BulkRecipientInput(
                id = newId(),
                name = listOf("Ada", "Tunde", "Alex", "Chidi")[index],
                recipientReference =
                    when (service) {
                        BulkService.Airtime,
                        BulkService.Data -> "0803000000${index + 1}"
                        else -> "0123456789${index + 1}"
                    },
                provider = providers[index % providers.size],
                service = service,
                amount = if (service == BulkService.Electricity) "4500" else "1500",
                product = products(service).firstOrNull().orEmpty(),
            )
        }
    }
}
