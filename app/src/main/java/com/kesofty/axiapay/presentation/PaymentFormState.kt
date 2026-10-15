package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import java.time.Clock
import java.time.LocalDate

data class PaymentFormState(
    val requestId: String,
    val route: String,
    val input: PaymentInput,
    val reviewing: Boolean = false,
    val smsSim: Int = 1,
    val minimumDate: LocalDate = input.date,
    val validation: PaymentValidation =
        PaymentValidation(null, false, true, DemoError.InvalidAmount),
    val providerPicker: ProviderPickerState = ProviderPickerState(),
) {
    /** Keeps electricity-specific rendering independent of application-layer input types. */
    val usesElectricityProviderPicker
        get() = isElectricity

    /** Exposes electricity-specific fields without UI imports from the application layer. */
    val isElectricity
        get() = input.type == PaymentType.Electricity

    /** Only immediate service payments can expand into a same-service recipient list. */
    val supportsMoreRecipients
        get() =
            !input.scheduled &&
                input.type in
                    listOf(
                        PaymentType.Airtime,
                        PaymentType.Data,
                        PaymentType.Electricity,
                        PaymentType.TV,
                    )

    val offlineEligible
        get() = DemoSmsEligibility.supports(input)

    val upcomingDates
        get() = input.recurrence.upcomingDates(input.date)

    val isFunding
        get() = input.type == PaymentType.Funding

    val isSend
        get() = input.type == PaymentType.Transfer

    val service
        get() = input.type.name

    val destination
        get() =
            when (input.destination) {
                TransferDestination.Wallet -> "AxiaPay wallet"
                TransferDestination.Bank -> "Bank account"
            }

    val providerOptions
        get() = DemoCatalog.providers(input.type)

    val recipientLabel
        get() =
            when {
                isSend && input.destination == TransferDestination.Wallet ->
                    "Recipient wallet handle"
                isSend -> "Account number"
                input.type == PaymentType.Electricity -> "Meter number"
                input.type == PaymentType.TV -> "Smartcard / IUC number"
                else -> "Phone number"
            }

    val recipientPlaceholder
        get() =
            when {
                isSend && input.destination == TransferDestination.Wallet -> "@alex"
                input.type == PaymentType.Electricity || input.type == PaymentType.TV || isSend ->
                    "Enter account number"
                else -> "0800 000 0000"
            }

    val recipientHelp
        get() =
            when {
                isSend && input.destination == TransferDestination.Wallet ->
                    "Use 3–30 letters, numbers or underscores."
                isSend -> "Enter a 10-digit account number."
                input.type == PaymentType.Electricity || input.type == PaymentType.TV ->
                    "Enter 10–13 digits."
                else -> "Enter an 11-digit number starting with 0."
            }

    val title
        get() =
            when {
                isFunding -> "Add money"
                isSend -> "Send money"
                else -> "Buy ${service.lowercase()}"
            }

    val reviewTitle
        get() =
            when {
                isFunding -> "Demo wallet funding"
                isSend && input.destination == TransferDestination.Bank -> input.provider
                isSend -> destination
                else -> "${input.provider} $service"
            }
}

/** Pure form reducer. It delegates validation/quotes to the application layer. */
class PaymentFormPresenter(private val prepare: PreparePayment, private val clock: Clock) {
    fun create(route: String, id: String): PaymentFormState {
        val type =
            when (route) {
                "Add money" -> PaymentType.Funding
                "Send money" -> PaymentType.Transfer
                "Schedule payment" -> PaymentType.Airtime
                else -> PaymentType.valueOf(route)
            }
        val input =
            PaymentInput(
                type,
                provider = defaultProvider(type),
                amount = defaultAmount(type),
                scheduled = route == "Schedule payment",
                date = LocalDate.now(clock).plusDays(1),
            )
        return refresh(PaymentFormState(id, route, input))
    }

    fun refresh(form: PaymentFormState) =
        form.copy(
            validation = prepare.validate(form.input),
            minimumDate = LocalDate.now(clock).plusDays(1),
            providerPicker =
                form.providerPicker.takeIf {
                    form.usesElectricityProviderPicker && !form.reviewing
                } ?: ProviderPickerState(),
        )

    fun reduce(form: PaymentFormState, intent: PaymentIntent): PaymentFormState {
        val input = form.input
        val updated =
            when (intent) {
                is PaymentIntent.ProviderPicker ->
                    form.copy(
                        providerPicker = reduceProviderPicker(form.providerPicker, intent.intent),
                    )
                is PaymentIntent.Service -> changeService(form, PaymentType.valueOf(intent.value))
                is PaymentIntent.Provider -> form.copy(input = input.copy(provider = intent.value))
                is PaymentIntent.Destination -> changeDestination(form, intent.value)
                is PaymentIntent.Recipient ->
                    form.copy(input = input.copy(recipient = intent.value.take(30)))
                is PaymentIntent.MeterType ->
                    form.copy(input = input.copy(electricityMeterType = intent.value))
                is PaymentIntent.Amount ->
                    form.copy(input = input.copy(amount = intent.value.take(10)))
                is PaymentIntent.DataPlan ->
                    form.copy(
                        input =
                            input.copy(
                                dataPlan = intent.value,
                                amount = DemoCatalog.dataAmount(intent.value).orEmpty(),
                            ),
                    )
                is PaymentIntent.Schedule ->
                    form.copy(input = input.copy(scheduled = intent.enabled))
                is PaymentIntent.Frequency ->
                    form.copy(input = input.copy(recurrence = Recurrence.valueOf(intent.value)))
                is PaymentIntent.Date -> form.copy(input = input.copy(date = intent.value))
                is PaymentIntent.Sim -> form.copy(smsSim = intent.value)
                PaymentIntent.Review -> form.copy(reviewing = prepare.validate(input).valid)
                PaymentIntent.Edit -> form.copy(reviewing = false)
            }
        return refresh(updated)
    }

    private fun changeService(form: PaymentFormState, type: PaymentType) =
        form.copy(
            providerPicker = ProviderPickerState(),
            input =
                form.input.copy(
                    type = type,
                    provider = defaultProvider(type),
                    amount = defaultAmount(type),
                    dataPlan = "8 GB",
                    electricityMeterType = ElectricityMeterType.Prepaid,
                ),
        )

    private fun changeDestination(form: PaymentFormState, value: String): PaymentFormState {
        val destination =
            when (value) {
                "Bank account" -> TransferDestination.Bank
                else -> TransferDestination.Wallet
            }
        val provider =
            when (destination) {
                TransferDestination.Bank -> "GTBank"
                else -> form.input.provider
            }
        return form.copy(
            input = form.input.copy(destination = destination, recipient = "", provider = provider),
        )
    }

    private fun defaultProvider(type: PaymentType) =
        DemoCatalog.providers(type).firstOrNull() ?: "MTN"

    private fun defaultAmount(type: PaymentType) =
        when (type) {
            PaymentType.Data -> "3500"
            else -> ""
        }
}
