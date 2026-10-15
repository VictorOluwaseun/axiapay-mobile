package com.kesofty.axiapay.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders text choices for banks, payment terms, destinations and recurrence with explicit
 * selection callbacks; service providers use the shared logo selector.
 */
@Composable
private fun ChoiceRow(
    options: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { label ->
            FilterChip(
                selected == label,
                { onSelect(label) },
                { Text(label, style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(12.dp),
                border = controlBorder(selected == label),
            )
        }
    }
}

/**
 * Renders the existing single-payment form, with an explicit entry to a same-service recipient
 * list.
 */
@Composable
fun PaymentSheet(
    form: PaymentFormState,
    state: DemoSession,
    onIntent: (PaymentIntent) -> Unit,
    confirm: () -> Unit,
    saveDraft: () -> Unit,
    addRecipient: () -> Unit,
) {
    val icon =
        when {
            form.isFunding -> Glyph.Wallet
            form.isSend && form.destination == "Bank account" -> Glyph.Bank
            form.isSend -> Glyph.Person
            else -> services.find { it.name == form.service }?.icon ?: Glyph.Phone
        }
    val subtitle =
        when {
            form.reviewing -> "Check the details before you continue."
            form.isFunding -> "A little more room for the everyday."
            else -> "Small payments. One less thing to think about."
        }
    SheetContent {
        IconTile(icon, tint = Mint)
        SheetHeading(if (form.reviewing) "Looks good?" else form.title, subtitle)
        if (state.offline) OfflineFormNote(form.offlineEligible, state.cachedTime)
        when {
            form.reviewing && form.validation.valid ->
                PaymentReview(form, state, onIntent, confirm, saveDraft)
            else -> PaymentFields(form, state, onIntent, addRecipient)
        }
        if (state.offline)
            TextButton(saveDraft, Modifier.align(Alignment.CenterHorizontally)) {
                Text("Save draft for later")
            }
    }
}

@Composable
private fun ColumnScope.PaymentReview(
    form: PaymentFormState,
    state: DemoSession,
    onIntent: (PaymentIntent) -> Unit,
    confirm: () -> Unit,
    saveDraft: () -> Unit,
) {
    if (state.offline) {
        OfflinePaymentReview(form, state, onIntent, confirm, saveDraft)
        return
    }
    val input = form.input
    val amount = requireNotNull(form.validation.amount)
    val recurring = input.scheduled && input.recurrence != Recurrence.Once
    MoneyAmount(money(amount, true), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    Column(Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(20.dp)).padding(16.dp)) {
        InfoRow("Payment", form.reviewTitle)
        if (!form.isFunding) InfoRow("Recipient", input.recipient)
        if (form.service == "Data") InfoRow("Sample plan", "${input.dataPlan} • 30 days")
        if (form.isElectricity)
            InfoRow(
                stringResource(R.string.electricity_meter_type),
                stringResource(electricityMeterTypeLabel(input.electricityMeterType)),
            )
        InfoRow("Fee", "₦0.00 (demo)")
        InfoRow(
            if (recurring) "First payment" else "When",
            if (input.scheduled) input.date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
            else "Now",
        )
        if (input.scheduled) InfoRow("Repeats", input.recurrence.label)
        if (recurring) InfoRow("Ends", "When paused")
        InfoRow("Wallet balance", if (state.hidden) "••••" else money(state.balance, true))
    }
    if (!form.isFunding && !input.scheduled && amount > state.balance) {
        Text(
            "Your wallet needs ${money(amount - state.balance)} more. Save this request and fund your wallet to pay it later.",
            color = Warning,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (recurring) {
        Text(
            "${money(amount, true)} per payment. ${input.recurrence.description(input.date)}. Your wallet won't be debited now.",
            style = MaterialTheme.typography.bodyMedium,
            color = Pine,
        )
    }
    Text(
        "Interactive demo. No real money moves and no provider account is verified.",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    val label =
        when {
            form.isFunding -> "Add demo money"
            input.scheduled -> "Save demo schedule"
            amount > state.balance -> "Save until funded"
            else -> "Confirm demo payment"
        }
    PrimaryButton(label, onClick = confirm)
    TextButton({ onIntent(PaymentIntent.Edit) }, Modifier.align(Alignment.CenterHorizontally)) {
        Text("Edit details")
    }
}

@Composable
private fun ColumnScope.PaymentFields(
    form: PaymentFormState,
    state: DemoSession,
    onIntent: (PaymentIntent) -> Unit,
    addRecipient: () -> Unit,
) {
    ServiceChoices(form, onIntent)
    if (form.isElectricity)
        ElectricityMeterTypeChoices(
            form.input.electricityMeterType,
            { onIntent(PaymentIntent.MeterType(it)) },
        )
    if (!form.isFunding) RecipientField(form, onIntent)
    if (form.service == "Data") {
        Text("Sample monthly plans", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("2 GB", "8 GB", "20 GB"), form.input.dataPlan) {
            onIntent(PaymentIntent.DataPlan(it))
        }
    }
    AmountField(form, state, onIntent)
    if (form.service != "Data")
        ChoiceRow(listOf("1000", "2000", "5000", "10000"), form.input.amount) {
            onIntent(PaymentIntent.Amount(it))
        }
    if (form.supportsMoreRecipients)
        OutlinedButton(
            addRecipient,
            enabled = form.validation.valid,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            border = controlBorder(),
            shape = RoundedCornerShape(16.dp),
        ) {
            AxiaIcon(Glyph.Plus, Modifier.size(20.dp), Pine)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.bulk_add_recipient))
        }
    if (!form.isFunding) ScheduleFields(form, onIntent)
    PrimaryButton(
        "Review ${if (form.isFunding) "top-up" else "payment"}",
        enabled = form.validation.valid,
    ) {
        onIntent(PaymentIntent.Review)
    }
    Text(
        "UI preview • Demo funds only",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

/** Uses a searchable electricity selector, compact service logos and textual bank choices. */
@Composable
private fun ServiceChoices(form: PaymentFormState, onIntent: (PaymentIntent) -> Unit) {
    if (form.route == "Schedule payment")
        ChoiceRow(listOf("Airtime", "Data", "Electricity", "TV"), form.service) {
            onIntent(PaymentIntent.Service(it))
        }
    if (form.isSend)
        ChoiceRow(listOf("AxiaPay wallet", "Bank account"), form.destination) {
            onIntent(PaymentIntent.Destination(it))
        }
    val showProviders = !form.isFunding && (!form.isSend || form.destination == "Bank account")
    if (!showProviders) return
    if (form.isSend) {
        ChoiceRow(form.providerOptions, form.input.provider) {
            onIntent(PaymentIntent.Provider(it))
        }
        return
    }
    if (form.usesElectricityProviderPicker) {
        ElectricityProviderSelector(
            form.providerOptions,
            form.input.provider,
            form.providerPicker,
            onPickerIntent = { onIntent(PaymentIntent.ProviderPicker(it)) },
            onSelect = { onIntent(PaymentIntent.Provider(it)) },
        )
        return
    }
    ProviderChoices(
        form.providerOptions,
        form.input.provider,
        showInternationalAirtime = form.showsInternationalAirtime,
    ) {
        onIntent(PaymentIntent.Provider(it))
    }
}

@Composable
private fun RecipientField(form: PaymentFormState, onIntent: (PaymentIntent) -> Unit) {
    val input = form.input
    val showError = input.recipient.isNotBlank() && !form.validation.recipientValid
    val support: (@Composable () -> Unit)? =
        when {
            showError -> {
                { Text(form.recipientHelp) }
            }
            else -> null
        }
    OutlinedTextField(
        input.recipient,
        { onIntent(PaymentIntent.Recipient(it)) },
        Modifier.fillMaxWidth(),
        label = { Text(form.recipientLabel) },
        placeholder = { Text(form.recipientPlaceholder) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    if (form.isSend && form.destination == "AxiaPay wallet") KeyboardType.Text
                    else KeyboardType.Phone,
            ),
        isError = showError,
        supportingText = support,
    )
}

@Composable
private fun AmountField(
    form: PaymentFormState,
    state: DemoSession,
    onIntent: (PaymentIntent) -> Unit,
) {
    val invalidAmount = form.input.amount.isNotBlank() && form.validation.amount == null
    val help =
        when {
            invalidAmount -> "Enter ₦1–₦1,000,000, with up to 2 decimal places."
            form.service == "Data" || form.service == "TV" ->
                "Illustrative pricing for this UI preview."
            state.offline ->
                "Cached: ${if (state.hidden) "••••" else money(state.balance, true)} • Not live"
            else -> "Available: ${if (state.hidden) "••••" else money(state.balance, true)}"
        }
    OutlinedTextField(
        form.input.amount,
        { onIntent(PaymentIntent.Amount(it)) },
        Modifier.fillMaxWidth(),
        label = { Text("Amount (₦)") },
        placeholder = { Text("0.00") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        readOnly = form.service == "Data",
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = invalidAmount,
        supportingText = { Text(help) },
    )
}

@Composable
private fun ScheduleFields(form: PaymentFormState, onIntent: (PaymentIntent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AxiaIcon(Glyph.Calendar, Modifier.size(21.dp), Pine)
        Text(
            "Schedule or repeat",
            Modifier.weight(1f).padding(start = 12.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        Switch(
            form.input.scheduled,
            { onIntent(PaymentIntent.Schedule(it)) },
            modifier = Modifier.semantics { contentDescription = "Schedule or repeat" },
        )
    }
    if (!form.input.scheduled) return
    ScheduleOptions(form, onIntent)
}

@Composable
private fun ScheduleOptions(form: PaymentFormState, onIntent: (PaymentIntent) -> Unit) {
    val input = form.input
    val context = LocalContext.current
    val pickerTheme =
        if (LocalOfflinePalette.current) com.kesofty.axiapay.R.style.AxiaDatePicker else 0
    Text("Repeat", style = MaterialTheme.typography.titleSmall)
    ChoiceRow(
        Recurrence.entries.map { it.label },
        input.recurrence.label,
        Modifier.testTag("repeat-frequency"),
    ) { label ->
        onIntent(PaymentIntent.Frequency(Recurrence.entries.first { it.label == label }.name))
    }
    OutlinedButton(
        {
            DatePickerDialog(
                    context,
                    pickerTheme,
                    { _, year, month, day ->
                        onIntent(PaymentIntent.Date(LocalDate.of(year, month + 1, day)))
                    },
                    input.date.year,
                    input.date.monthValue - 1,
                    input.date.dayOfMonth,
                )
                .apply {
                    datePicker.minDate =
                        form.minimumDate
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                }
                .show()
        },
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            "${if (input.recurrence == Recurrence.Once) "Pay on" else "Starts"} ${input.date.format(DateTimeFormatter.ofPattern("EEEE, d MMM"))}",
        )
    }
    RecurrencePreview(form)
    if (!form.validation.dateValid)
        Text(
            "Choose a future payment date.",
            color = Danger,
            style = MaterialTheme.typography.bodySmall,
        )
    Text(
        if (input.recurrence == Recurrence.Once) "Your wallet won't be debited now."
        else "The same amount repeats until you pause this plan. Your wallet won't be debited now.",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
}

@Composable
private fun RecurrencePreview(form: PaymentFormState) {
    val recurrence = form.input.recurrence
    val firstPayment = form.input.date
    Column(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AxiaIcon(
                if (recurrence == Recurrence.Once) Glyph.Calendar else Glyph.Convert,
                Modifier.size(18.dp),
                Pine,
            )
            Text(recurrence.description(firstPayment), style = MaterialTheme.typography.titleSmall)
        }
        if (recurrence == Recurrence.Once) return@Column
        UpcomingDates(form.upcomingDates)
        if (recurrence == Recurrence.Monthly && firstPayment.dayOfMonth > 28) {
            Text(
                "Shorter months use their last day, then return to your chosen day.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun UpcomingDates(dates: List<LocalDate>) {
    Text("Next payments", style = MaterialTheme.typography.bodySmall, color = Muted)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        dates.forEach { date ->
            Column(
                Modifier.weight(1f)
                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(10.dp))
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("d MMM")),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    date.year.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}
