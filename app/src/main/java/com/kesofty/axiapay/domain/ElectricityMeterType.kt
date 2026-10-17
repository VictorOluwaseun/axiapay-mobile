package com.kesofty.axiapay.domain

/**
 * Electricity billing terms selected by the customer; live provider eligibility is server-owned.
 */
enum class ElectricityMeterType {
    /** Purchases electricity credit for a prepaid meter. */
    Prepaid,
    /** Pays an electricity bill for a postpaid account. */
    Postpaid,
}
