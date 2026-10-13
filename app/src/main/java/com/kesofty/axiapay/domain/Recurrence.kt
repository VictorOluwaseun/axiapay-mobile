package com.kesofty.axiapay.domain

import java.time.LocalDate

/** Anchor every occurrence to the original date, including month-end and leap-year recovery. */
enum class Recurrence {
    Once,
    Daily,
    Weekly,
    Monthly;

    fun upcomingDates(firstPayment: LocalDate, count: Int = 3): List<LocalDate> {
        require(count > 0)
        val occurrences =
            when (this) {
                Once -> 1
                else -> count
            }
        return List(occurrences) { index ->
            when (this) {
                Once -> firstPayment
                Daily -> firstPayment.plusDays(index.toLong())
                Weekly -> firstPayment.plusWeeks(index.toLong())
                Monthly -> firstPayment.plusMonths(index.toLong())
            }
        }
    }
}
