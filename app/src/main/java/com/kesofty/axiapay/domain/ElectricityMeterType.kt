package com.kesofty.axiapay.domain

/**
 * Electricity billing terms selected by the customer; live provider eligibility is server-owned.
 */
enum class ElectricityMeterType {
    /** Purchases electricity credit for a prepaid meter in the demo. */
    Prepaid,
    /** Pays an electricity bill for a postpaid account in the demo. */
    Postpaid,
}
