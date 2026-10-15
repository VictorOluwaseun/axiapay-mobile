package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import java.time.Clock
import java.time.LocalDate

enum class PaymentType {
    Funding,
    Transfer,
    Airtime,
    Data,
    Electricity,
    TV,
}

enum class TransferDestination {
    Wallet,
    Bank,
}

/** Raw client input is revalidated at submission, never trusted because a button was enabled. */
data class PaymentInput(
    val type: PaymentType,
    val provider: String = "MTN",
    val recipient: String = "",
    val amount: String = "",
    val destination: TransferDestination = TransferDestination.Wallet,
    val dataPlan: String = "8 GB",
    val scheduled: Boolean = false,
    val date: LocalDate,
    val recurrence: Recurrence = Recurrence.Once,
    val electricityMeterType: ElectricityMeterType = ElectricityMeterType.Prepaid,
)

data class PaymentValidation(
    val amount: Long?,
    val recipientValid: Boolean,
    val dateValid: Boolean,
    val error: DemoError?,
) {
    val valid: Boolean
        get() = error == null
}

/** Illustrative providers and quotes belong to the demo application, not to a Composable. */
object DemoCatalog {
    /** Shares demo provider identities across individual, scheduled, bulk and imported forms. */
    fun providers(type: PaymentType): List<String> =
        when (type) {
            PaymentType.TV -> listOf("DStv", "GOtv", "StarTimes")
            PaymentType.Electricity ->
                listOf(
                    "Ikeja Electric",
                    "Eko Electric",
                    "Abuja Electric",
                    "Kano Electric",
                    "Port Harcourt Electric",
                    "Jos Electric",
                    "Kaduna Electric",
                    "Enugu Electric",
                    "Ibadan Electric",
                    "Benin Electric",
                    "Aba Electric",
                    "Yola Electric",
                )
            PaymentType.Transfer -> listOf("GTBank", "Access", "UBA")
            PaymentType.Funding -> emptyList()
            PaymentType.Airtime,
            PaymentType.Data -> listOf("MTN", "Airtel", "Glo", "T2 (9mobile)", "Smile")
        }

    fun dataAmount(plan: String): String? =
        when (plan) {
            "2 GB" -> "1000"
            "8 GB" -> "3500"
            "20 GB" -> "7000"
            else -> null
        }
}

class PreparePayment(private val clock: Clock) {
    fun validate(input: PaymentInput): PaymentValidation {
        val amount = AmountPolicy.parse(input.amount)
        val recipientValid = validRecipient(input)
        val dateValid = !input.scheduled || input.date.isAfter(LocalDate.now(clock))
        val error =
            when {
                amount == null -> DemoError.InvalidAmount
                !recipientValid -> DemoError.InvalidRecipient
                !dateValid -> DemoError.InvalidDate
                !validProvider(input) -> DemoError.InvalidProvider
                input.type == PaymentType.Data &&
                    DemoCatalog.dataAmount(input.dataPlan) != input.amount -> DemoError.InvalidPlan
                else -> null
            }
        return PaymentValidation(amount, recipientValid, dateValid, error)
    }

    operator fun invoke(input: PaymentInput, requestId: String): Outcome<PaymentRequest> {
        val validation = validate(input)
        val error = validation.error
        if (error != null) return Outcome.Failure(error)
        val kind = kind(input)
        val title =
            when {
                input.type == PaymentType.Funding -> "Money added"
                input.type == PaymentType.Transfer && input.scheduled -> "Transfer"
                input.type == PaymentType.Transfer -> "Money sent"
                input.scheduled -> "${input.provider} ${input.type.name}"
                else -> "${input.provider} ${input.type.name.lowercase()}"
            }
        val detail =
            when (input.type) {
                PaymentType.Funding -> "Demo wallet funding"
                PaymentType.Transfer -> "${transferTarget(input)} • ${input.recipient}"
                PaymentType.Data -> "${input.provider} • ${input.dataPlan} • ${input.recipient}"
                PaymentType.Electricity ->
                    "${input.provider} • ${input.electricityMeterType.name} • ${input.recipient}"
                else -> "${input.provider} • ${input.recipient}"
            }
        val date = input.date.takeIf { input.scheduled && input.type != PaymentType.Funding }
        val recurrence =
            when (date) {
                null -> Recurrence.Once
                else -> input.recurrence
            }
        return Outcome.Success(
            PaymentRequest(
                requestId,
                title,
                detail,
                requireNotNull(validation.amount),
                kind,
                date,
                recurrence,
            ),
        )
    }

    private fun validRecipient(input: PaymentInput): Boolean =
        when {
            input.type == PaymentType.Funding -> true
            input.type == PaymentType.Transfer && input.destination == TransferDestination.Wallet ->
                Regex("@?[A-Za-z0-9_]{3,30}").matches(input.recipient.trim())
            input.type == PaymentType.Transfer -> Regex("[0-9]{10}").matches(input.recipient)
            input.type == PaymentType.Electricity || input.type == PaymentType.TV ->
                Regex("[0-9]{10,13}").matches(input.recipient)
            else -> Regex("0[0-9]{10}").matches(input.recipient)
        }

    private fun validProvider(input: PaymentInput): Boolean {
        if (input.type == PaymentType.Funding) return true
        if (input.type == PaymentType.Transfer && input.destination == TransferDestination.Wallet)
            return true
        return input.provider in DemoCatalog.providers(input.type)
    }

    private fun transferTarget(input: PaymentInput): String =
        when (input.destination) {
            TransferDestination.Wallet -> "AxiaPay wallet"
            TransferDestination.Bank -> input.provider
        }

    private fun kind(input: PaymentInput): PaymentKind =
        when (input.type) {
            PaymentType.Funding -> PaymentKind.Funding
            PaymentType.Airtime -> PaymentKind.Airtime
            PaymentType.Data -> PaymentKind.Data
            PaymentType.Electricity -> PaymentKind.Electricity
            PaymentType.TV -> PaymentKind.Tv
            PaymentType.Transfer ->
                when (input.destination) {
                    TransferDestination.Wallet -> PaymentKind.WalletTransfer
                    TransferDestination.Bank -> PaymentKind.BankTransfer
                }
        }
}
