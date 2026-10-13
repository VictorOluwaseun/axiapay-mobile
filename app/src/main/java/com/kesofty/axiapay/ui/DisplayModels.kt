package com.kesofty.axiapay.ui

import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.ui.components.Glyph
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Service(val name: String, val icon: Glyph, val hint: String)

val services =
    listOf(
        Service("Airtime", Glyph.Phone, "A little more talk time"),
        Service("Data", Glyph.Wifi, "Keep your world connected"),
        Service("Electricity", Glyph.Bolt, "Keep the lights on"),
        Service("TV", Glyph.Tv, "More of what you love"),
        Service("Education", Glyph.School, "Invest in your next chapter"),
        Service("Insurance", Glyph.Shield, "Cover for the everyday"),
        Service("Convert airtime", Glyph.Convert, "Turn airtime into wallet funds"),
        Service("Merchants", Glyph.Grid, "Pay your favourite businesses"),
    )

fun money(kobo: Long, decimals: Boolean = false): String {
    val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-NG"))
    formatter.minimumFractionDigits = if (decimals) 2 else 0
    formatter.maximumFractionDigits = if (decimals) 2 else 0
    return "₦" + formatter.format(BigDecimal(kobo).movePointLeft(2))
}

val Payment.icon: Glyph
    get() = kind.icon
val BillPlan.icon: Glyph
    get() = kind.icon
private val PaymentKind.icon: Glyph
    get() =
        when (this) {
            PaymentKind.Airtime -> Glyph.Phone
            PaymentKind.Data -> Glyph.Wifi
            PaymentKind.Electricity -> Glyph.Bolt
            PaymentKind.Tv -> Glyph.Tv
            PaymentKind.Funding -> Glyph.ArrowDown
            PaymentKind.WalletTransfer -> Glyph.Person
            PaymentKind.BankTransfer -> Glyph.Bank
            PaymentKind.Bulk -> Glyph.Batch
        }
val PaymentStatus.label: String
    get() =
        when (this) {
            PaymentStatus.Successful -> "Successful"
            PaymentStatus.Waiting -> "Waiting for funds"
            PaymentStatus.Scheduled -> "Scheduled"
            PaymentStatus.Failed -> "Failed"
            PaymentStatus.Processing -> "Processing"
        }
val Recurrence.label: String
    get() =
        when (this) {
            Recurrence.Once -> "One-time"
            Recurrence.Daily -> "Daily"
            Recurrence.Weekly -> "Weekly"
            Recurrence.Monthly -> "Monthly"
        }

fun Recurrence.description(firstPayment: LocalDate): String =
    when (this) {
        Recurrence.Once -> "One-time payment"
        Recurrence.Daily -> "Every day"
        Recurrence.Weekly ->
            "Every ${firstPayment.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))}"
        Recurrence.Monthly -> "Every month on day ${firstPayment.dayOfMonth}"
    }
