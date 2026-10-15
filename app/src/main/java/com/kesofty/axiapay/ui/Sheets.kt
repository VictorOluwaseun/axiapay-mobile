package com.kesofty.axiapay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*
import kotlin.math.abs

@Composable
internal fun SheetContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
internal fun SheetHeading(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
fun ReceiptSheet(
    payment: Payment,
    state: DemoSession,
    addMoney: () -> Unit,
    close: () -> Unit,
    complete: () -> Unit,
) {
    SheetContent {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.size(76.dp)
                .align(Alignment.CenterHorizontally)
                .background(
                    if (payment.status == PaymentStatus.Waiting) Peach else Mint,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AxiaIcon(
                when (payment.status) {
                    PaymentStatus.Waiting -> Glyph.Clock
                    PaymentStatus.Scheduled -> Glyph.Calendar
                    else -> Glyph.Check
                },
                Modifier.size(35.dp),
                Pine,
            )
        }
        Text(
            when (payment.status) {
                PaymentStatus.Waiting -> "Ready when you are."
                PaymentStatus.Scheduled -> "A little peace of mind."
                else -> "All taken care of."
            },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        MoneyAmount(
            money(abs(payment.amount), true),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Box(Modifier.align(Alignment.CenterHorizontally)) { StatusPill(payment.status) }
        Column(
            Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(22.dp)).padding(18.dp),
        ) {
            InfoRow("Payment", payment.title)
            InfoRow("Details", payment.detail)
            InfoRow(
                if (payment.status == PaymentStatus.Scheduled) "First payment" else "When",
                payment.time,
            )
            if (payment.status == PaymentStatus.Scheduled)
                InfoRow("Repeats", payment.recurrence.label)
            if (payment.status == PaymentStatus.Scheduled && payment.recurrence != Recurrence.Once)
                InfoRow("Amount", "${money(abs(payment.amount))} per payment")
            InfoRow("Reference", "AX-${payment.id.take(8).uppercase()}")
        }
        Text(
            when (payment.status) {
                PaymentStatus.Waiting ->
                    "No money has been deducted. Add funds, then return to this payment in Activity to complete it."
                PaymentStatus.Scheduled ->
                    "Your demo plan is saved. Automatic execution will be available when the payment service is connected."
                else -> "This is a demo receipt. No real money moved."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        when {
            payment.status != PaymentStatus.Waiting -> Unit
            state.balance >= -payment.amount ->
                PrimaryButton("Complete demo payment", onClick = complete)
            else -> PrimaryButton("Add money", onClick = addMoney)
        }
        PrimaryButton("Done") { close() }
    }
}

@Composable
fun NotificationsSheet(state: DemoSession, addMoney: () -> Unit, plans: () -> Unit) {
    SheetContent {
        SheetHeading("A friendly heads-up.", "The useful things, right when you need them.")
        NotificationCard(
            Glyph.Wifi,
            "Stay connected tomorrow",
            "Your sample MTN plan is due to renew tomorrow. Actual data usage isn't available in this preview.",
            "View plans",
            plans,
        )
        val waiting = state.payments.count { it.status == PaymentStatus.Waiting }
        val paymentLabel = if (waiting == 1) "payment" else "payments"
        if (waiting > 0)
            NotificationCard(
                Glyph.Wallet,
                "$waiting $paymentLabel waiting",
                "Add money to your wallet, then complete your queued payment from Activity.",
                "Add money",
                addMoney,
            )
        NotificationCard(
            Glyph.Calendar,
            "Your plans are in good company",
            "Manage upcoming payments and pause any plan whenever you need to.",
            "Manage plans",
            plans,
        )
        Text(
            "Sample notifications. Push delivery and data-balance monitoring will be connected later.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun NotificationCard(
    icon: Glyph,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(icon)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted)
        if (action.isNotEmpty())
            TextButton(onClick, contentPadding = PaddingValues(0.dp)) {
                Text(action)
                Spacer(Modifier.width(8.dp))
                AxiaIcon(Glyph.ArrowRight, Modifier.size(18.dp), Pine)
            }
    }
}

/** Renders demo profile preferences and emits changes through typed intents. */
@Composable
fun ProfileSheet(state: DemoSession, onIntent: (AxiaIntent) -> Unit, onPreviewAuth: () -> Unit) {
    SheetContent {
        IconTile(Glyph.Person, tint = Peach)
        SheetHeading("A space for you.", "Alex Morgan • @alex • Demo profile")
        TextButton(onPreviewAuth, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.auth_preview_shortcut),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        InfoRow("Wallet", "NGN wallet")
        InfoRow("Account", "Interactive preview")
        ConnectionStatus(state.offline, state.connectionMode)
        ConnectionModeControls(state.connectionMode, onIntent)
        TextButton({ onIntent(AxiaIntent.OpenService("offline")) }) { Text("Open SMS outbox") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Hide balances", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(
                state.hidden,
                { onIntent(AxiaIntent.HideBalance(it)) },
                modifier = Modifier.semantics { contentDescription = "Hide balances" },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Payment reminders",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(
                state.reminderEnabled,
                { onIntent(AxiaIntent.SetReminders(it)) },
                modifier = Modifier.semantics { contentDescription = "Payment reminders" },
            )
        }
        Text(
            "Welcome to the first AxiaPay UI preview. Explore services, try a demo payment, and plan ahead. This sample profile isn't a real customer account.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
    }
}

@Composable
fun ExploreSheet(service: String) {
    val item = services.first { it.name == service }
    SheetContent {
        IconTile(item.icon)
        SheetHeading(service, item.hint)
        val options =
            when (service) {
                "Education" ->
                    listOf(
                        "Exam payments" to "WAEC, NECO and JAMB",
                        "School fees" to "Participating schools and institutions",
                    )
                "Insurance" ->
                    listOf(
                        "Motor cover" to "Protection for the road ahead",
                        "Health cover" to "Everyday care for you and your family",
                    )
                "Convert airtime" ->
                    listOf(
                        "Choose your network" to "See supported networks and conversion limits",
                        "Review your quote" to "Know the fees and wallet credit before confirming",
                        "Receive wallet funds" to "After the airtime transfer is verified",
                    )
                else ->
                    listOf(
                        "Pay a merchant" to "Find businesses or scan their payment code",
                        "Saved merchants" to "Keep the places you pay close by",
                    )
            }
        options.forEach { (title, description) ->
            Column(
                Modifier.fillMaxWidth()
                    .background(Canvas, RoundedCornerShape(18.dp))
                    .padding(18.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        Text(
            "Coming in a later build. Providers, eligibility and pricing aren't connected in this preview.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
    }
}
