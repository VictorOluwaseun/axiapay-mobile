package com.kesofty.axiapay.domain

import java.time.LocalDate

enum class Currency {
    NGN,
}

enum class PaymentKind {
    Airtime,
    Data,
    Electricity,
    Tv,
    Funding,
    WalletTransfer,
    BankTransfer,
    Bulk,
}

enum class PaymentStatus {
    Successful,
    Waiting,
    Scheduled,
    Failed,
    Processing,
}

/** All amounts are exact NGN minor units (kobo); outgoing activity is signed negative. */
data class Payment(
    val id: String,
    val title: String,
    val detail: String,
    val amount: Long,
    val kind: PaymentKind,
    val status: PaymentStatus = PaymentStatus.Successful,
    val time: String = "Just now",
    val recurrence: Recurrence = Recurrence.Once,
    val currency: Currency = Currency.NGN,
)

data class BillPlan(
    val id: String,
    val title: String,
    val detail: String,
    val amount: Long,
    val kind: PaymentKind,
    val date: LocalDate,
    val enabled: Boolean = true,
    val recurrence: Recurrence = Recurrence.Once,
)

/** A session snapshot is a value, never a mutable state holder owned by a View. */
data class DemoSession(
    val balance: Long,
    val payments: List<Payment> = emptyList(),
    val plans: List<BillPlan> = emptyList(),
    val hidden: Boolean = false,
    val notificationsRead: Boolean = false,
    val reminderEnabled: Boolean = true,
    val currency: Currency = Currency.NGN,
    val offline: Boolean = false,
    val cachedAt: java.time.Instant? = null,
    val smsOutbox: List<SmsRequest> = emptyList(),
    val connectionMode: ConnectionMode = ConnectionMode.Automatic,
)

data class PaymentRequest(
    val id: String,
    val title: String,
    val detail: String,
    val amount: Long,
    val kind: PaymentKind,
    val date: LocalDate? = null,
    val recurrence: Recurrence = Recurrence.Once,
)

sealed interface DemoError {
    data object OnlineRequired : DemoError

    data object SmsUnsupported : DemoError

    data object SmsAlreadySubmitted : DemoError

    data object SmsNotSent : DemoError

    data object RequestExpired : DemoError

    data object RetryLimit : DemoError

    data object InvalidAmount : DemoError

    data object InvalidRecipient : DemoError

    data object InvalidDate : DemoError

    data object InvalidProvider : DemoError

    data object InvalidPlan : DemoError

    data object NotFound : DemoError

    data object InsufficientFunds : DemoError

    data object RequestConflict : DemoError

    data class ConnectionMonitoring(val cause: Throwable) : DemoError

    data class Storage(val cause: Exception) : DemoError
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val error: DemoError) : Outcome<Nothing>
}

data class SessionChange<T>(val session: DemoSession, val value: T)
