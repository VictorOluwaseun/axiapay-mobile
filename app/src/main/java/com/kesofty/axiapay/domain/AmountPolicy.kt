package com.kesofty.axiapay.domain

import java.math.BigDecimal

/** Local preview limits only. Production limits and settlement belong to the server. */
object AmountPolicy {
    fun isValid(kobo: Long): Boolean = kobo in 100..100_000_000

    fun parse(text: String): Long? {
        if (!Regex("[0-9]{1,7}(\\.[0-9]{1,2})?").matches(text)) return null
        return BigDecimal(text).movePointRight(2).longValueExact().takeIf(::isValid)
    }
}
