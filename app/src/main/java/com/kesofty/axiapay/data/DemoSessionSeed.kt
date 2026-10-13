package com.kesofty.axiapay.data

import com.kesofty.axiapay.domain.*
import java.time.LocalDate

/** Original illustrative balance, activity and plans; never mistaken for server data. */
object DemoSessionSeed {
    fun create(today: LocalDate): DemoSession {
        val payments =
            listOf(
                Payment(
                    id = "sample-data",
                    title = "MTN data",
                    detail = "8 GB monthly plan",
                    amount = -350_000,
                    kind = PaymentKind.Data,
                    time = "Today, 9:41 AM",
                ),
                Payment(
                    id = "sample-funding",
                    title = "Money added",
                    detail = "Bank transfer",
                    amount = 5_000_000,
                    kind = PaymentKind.Funding,
                    time = "Today, 8:12 AM",
                ),
                Payment(
                    id = "sample-power",
                    title = "Electricity",
                    detail = "Ikeja Electric • Home",
                    amount = -1_000_000,
                    kind = PaymentKind.Electricity,
                    time = "Yesterday, 6:30 PM",
                ),
                Payment(
                    id = "sample-airtime",
                    title = "Airtel airtime",
                    detail = "0800 000 0000",
                    amount = -100_000,
                    kind = PaymentKind.Airtime,
                    time = "Yesterday, 2:08 PM",
                ),
            )
        val plans =
            listOf(
                BillPlan(
                    id = "plan-data",
                    title = "My monthly data",
                    detail = "MTN • 8 GB plan",
                    amount = 350_000,
                    kind = PaymentKind.Data,
                    date = today.plusDays(1),
                    recurrence = Recurrence.Monthly,
                ),
                BillPlan(
                    id = "plan-tv",
                    title = "Family movie nights",
                    detail = "DStv • Sample package",
                    amount = 1_100_000,
                    kind = PaymentKind.Tv,
                    date = today.plusDays(4),
                    recurrence = Recurrence.Monthly,
                ),
            )
        return DemoSession(balance = 12_845_000L, payments = payments, plans = plans)
    }
}
