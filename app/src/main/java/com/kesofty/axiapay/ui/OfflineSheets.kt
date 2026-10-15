package com.kesofty.axiapay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val DemoSession.cachedTime: String
    get() =
        cachedAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm"))
            ?: "last session"

/** Keeps offline status or an active demo override visible with a route to its controls. */
@Composable
internal fun ConnectionBanner(mode: ConnectionMode, open: () -> Unit) {
    Row(
        Modifier.widthIn(max = 600.dp)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = open)
            .background(Line)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AxiaIcon(
            if (mode == ConnectionMode.ForceOnline) Glyph.Wifi else Glyph.Offline,
            Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(mode.statusTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                if (mode == ConnectionMode.Automatic) "No internet • SMS demo available"
                else "Manual override • Demo only",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        Text("Manage", style = MaterialTheme.typography.labelMedium)
        AxiaIcon(Glyph.Chevron, Modifier.size(14.dp))
    }
}

/** Renders connection controls, a resumable draft and the demo SMS outbox without sending. */
@Composable
fun OfflineSheet(state: DemoSession, draft: PaymentFormState?, onIntent: (AxiaIntent) -> Unit) {
    val onlineExplanation =
        if (state.connectionMode == ConnectionMode.Automatic)
            "Offline mode turns on automatically when internet access is lost."
        else "Online preview is active. Choose Automatic above to follow your internet connection."
    SheetContent {
        IconTile(Glyph.Offline, tint = Mint)
        SheetHeading("A connection, another way.", "Your everyday payments, even without internet.")
        ConnectionStatus(state.offline, state.connectionMode)
        ConnectionModeControls(state.connectionMode, onIntent)
        Text(
            "Airtime and data work with SMS in this preview. You'll still need mobile signal and an SMS-enabled SIM.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        if (state.offline)
            PrimaryButton("Buy airtime by SMS") { onIntent(AxiaIntent.OpenService("Airtime")) }
        if (!state.offline)
            Text(
                onlineExplanation,
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        if (draft != null) {
            OutlinedButton(
                { onIntent(AxiaIntent.ResumeDraft) },
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Continue ${draft.service.lowercase()} draft")
            }
        }
        SectionHeading("SMS outbox")
        if (state.smsOutbox.isEmpty()) {
            Text(
                "Nothing sent yet. Review a payment first, then choose to send it by SMS.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.smsOutbox.forEach { request ->
            val payment = state.payments.find { it.id == request.id }
            SmsOutboxRow(request, state.hidden, payment?.status) {
                onIntent(AxiaIntent.OpenSms(request.id))
            }
        }
        HorizontalDivider(color = Line)
        Text("Sent is just the first step.", style = MaterialTheme.typography.titleMedium)
        Text(
            "An SMS goes to the AxiaPay gateway for a payment check. Wait for a confirmed result before trying a new payment. Reconnecting keeps the same request.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Demo only: no texts are sent. SMS charges may apply in a live service. Your drafts and outbox are kept for this demo session, including activity recreation; this is not a permanent offline queue.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.offline) ReconnectHint(state.connectionMode)
    }
}

@Composable
internal fun OfflineFormNote(eligible: Boolean, cachedTime: String) {
    Column(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (eligible) "Offline · Pay by SMS" else "Offline · Save a draft",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (eligible)
                "Sample prices and balance last seen at $cachedTime. Final availability and funds need confirmation."
            else
                "This service needs internet in the demo. Keep your details here and finish when you reconnect. Existing plans stay saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
    }
}

/** Shows cached payment details and outlined SIM choices, delegating selection and submission. */
@Composable
internal fun ColumnScope.OfflinePaymentReview(
    form: PaymentFormState,
    state: DemoSession,
    onIntent: (PaymentIntent) -> Unit,
    confirm: () -> Unit,
    saveDraft: () -> Unit,
) {
    MoneyAmount(
        money(requireNotNull(form.validation.amount), true),
        modifier = Modifier.fillMaxWidth(),
    )
    Column(Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(20.dp)).padding(16.dp)) {
        InfoRow("Payment", form.reviewTitle)
        if (!form.isFunding) InfoRow("Recipient", form.input.recipient)
        if (form.service == "Data") InfoRow("Sample plan", "${form.input.dataPlan} • 30 days")
        if (form.isElectricity)
            InfoRow(
                stringResource(R.string.electricity_meter_type),
                stringResource(electricityMeterTypeLabel(form.input.electricityMeterType)),
            )
        InfoRow("Cached balance", if (state.hidden) "••••" else money(state.balance, true))
    }
    if (form.offlineEligible) {
        Text("Sample SIM", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (1..2).forEach { sim ->
                SelectionChip("SIM $sim", form.smsSim == sim, { onIntent(PaymentIntent.Sim(sim)) })
            }
        }
        Text(
            "Choose a sample SIM. You can review the SMS route and payment limit before sending.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        PrimaryButton("Review SMS request", onClick = confirm)
    }
    if (!form.offlineEligible) PrimaryButton("Keep as a draft", onClick = saveDraft)
    TextButton({ onIntent(PaymentIntent.Edit) }, Modifier.align(Alignment.CenterHorizontally)) {
        Text("Edit details")
    }
}

@Composable
internal fun SmsOutboxRow(
    request: SmsRequest,
    hidden: Boolean,
    status: PaymentStatus? = null,
    open: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Canvas)
            .clickable(role = Role.Button, onClick = open)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(Glyph.Phone, tint = Line)
            Column(Modifier.weight(1f)) {
                Text(request.payment.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "SMS • SIM ${request.sim}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (hidden) "••••" else money(request.payment.amount),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(smsTitle(request, status), style = MaterialTheme.typography.labelMedium, color = Pine)
    }
}

private fun smsTitle(request: SmsRequest, status: PaymentStatus?): String =
    when {
        status == PaymentStatus.Successful -> "Payment confirmed · Demo"
        status == PaymentStatus.Waiting -> "Waiting for funds · Demo"
        request.transport == SmsTransport.Sent -> "Awaiting confirmation"
        request.transport == SmsTransport.SendFailed -> "SMS wasn't sent"
        else -> "Saved on this device"
    }

@Composable
fun SmsPaymentSheet(request: SmsRequest, state: DemoSession, onIntent: (AxiaIntent) -> Unit) {
    val status = state.payments.find { it.id == request.id }?.status
    val sent = request.transport == SmsTransport.Sent
    val failed = request.transport == SmsTransport.SendFailed
    SheetContent {
        IconTile(
            when {
                status == PaymentStatus.Successful -> Glyph.Check
                sent -> Glyph.Clock
                else -> Glyph.Phone
            },
            Modifier.size(60.dp),
            Mint,
        )
        SheetHeading(
            when {
                status != null -> smsTitle(request, status)
                failed -> "Let's try that again."
                sent -> "Sent. Waiting for a reply."
                else -> "Ready to send by SMS?"
            },
            when {
                status == PaymentStatus.Successful ->
                    "A simulated backend reply confirmed this payment."
                status == PaymentStatus.Waiting -> "The demo backend needs more wallet funds."
                failed -> "The demo SMS couldn't be sent. Nothing was submitted."
                sent -> "SMS sent in this demo. Your payment isn't confirmed yet."
                else -> "Review the details. Nothing has been sent yet."
            },
        )
        MoneyAmount(money(request.payment.amount, true), modifier = Modifier.fillMaxWidth())
        Column(
            Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(20.dp)).padding(16.dp),
        ) {
            InfoRow("Payment", request.payment.title)
            InfoRow("Details", request.payment.detail)
            InfoRow("Payment fee", "₦0.00 · Demo")
            InfoRow("Maximum debit", "${money(request.payment.amount, true)} NGN")
            InfoRow("Route", "AxiaPay SMS gateway · Demo")
            InfoRow("Send using", "SIM ${request.sim} · Demo")
            InfoRow(
                "Send before",
                request.expiresAt
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d MMM, HH:mm")),
            )
        }
        SmsProgress(request, status)
        SmsActions(request, state, status, onIntent)
        Text("Reference: ${request.id}", style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(
            "Simulation only. No SMS or real money moves. Carrier SMS fees would be separate from the payment limit.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun SmsProgress(request: SmsRequest, status: PaymentStatus?) {
    val sent = request.transport == SmsTransport.Sent
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProgressLine(Glyph.Check, "Request saved", "Your payment details have a unique reference.")
        ProgressLine(
            if (sent) Glyph.Check else Glyph.Phone,
            if (sent) "SMS sent · Simulated" else "SMS not sent",
            "SIM ${request.sim} • ${request.attempts} of 3 attempts",
        )
        ProgressLine(
            if (status == PaymentStatus.Successful) Glyph.Check else Glyph.Clock,
            when {
                status != null -> smsTitle(request, status)
                sent -> "Awaiting payment confirmation"
                else -> "Payment check comes next"
            },
            when {
                status == PaymentStatus.Successful -> "Demo wallet updated once."
                status == PaymentStatus.Waiting ->
                    "No debit yet. Add funds, then explicitly complete it online."
                else -> "Sending or delivery alone never confirms a payment."
            },
        )
    }
}

@Composable
private fun ProgressLine(icon: Glyph, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AxiaIcon(icon, Modifier.padding(top = 3.dp).size(20.dp), Pine)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

/** Exposes explicit demo transport/reconciliation actions and preserves uncertain outcomes. */
@Composable
private fun SmsActions(
    request: SmsRequest,
    state: DemoSession,
    status: PaymentStatus?,
    onIntent: (AxiaIntent) -> Unit,
) {
    when {
        status == PaymentStatus.Successful ->
            PrimaryButton("Done") { onIntent(AxiaIntent.CloseSheet) }
        status == PaymentStatus.Waiting && state.offline -> ReconnectHint(state.connectionMode)
        status == PaymentStatus.Waiting && state.balance >= request.payment.amount ->
            PrimaryButton("Complete demo payment") {
                onIntent(AxiaIntent.CompleteWaiting(request.id))
            }
        status == PaymentStatus.Waiting ->
            PrimaryButton("Add money") { onIntent(AxiaIntent.OpenService("Add money")) }
        request.transport == SmsTransport.Sent -> {
            Text(
                "Keep this request. A missing reply doesn't mean it failed. Check its status instead of paying again.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            PrimaryButton(if (state.offline) "Simulate backend reply" else "Check demo status") {
                onIntent(AxiaIntent.ReconcileSms(request.id))
            }
            if (state.offline) ReconnectHint(state.connectionMode)
        }
        else -> {
            PrimaryButton(
                if (request.transport == SmsTransport.SendFailed) "Retry same SMS request"
                else "Send demo SMS",
                enabled = request.attempts < 3,
            ) {
                onIntent(AxiaIntent.SendSms(request.id))
            }
            TextButton(
                { onIntent(AxiaIntent.SendSms(request.id, simulateFailure = true)) },
                enabled = request.attempts < 3,
            ) {
                Text("Try a failed-send scenario")
            }
            if (request.attempts >= 3)
                Text(
                    "Nothing was sent after three attempts. Start a new payment when mobile service is available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            TextButton({ onIntent(AxiaIntent.CloseSheet) }) { Text("Keep in outbox") }
        }
    }
}

/** Shows effective connection state and whether it follows the device or a demo override. */
@Composable
internal fun ConnectionStatus(offline: Boolean, mode: ConnectionMode) {
    Row(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(if (offline) Glyph.Offline else Glyph.Wifi)
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    mode != ConnectionMode.Automatic -> mode.statusTitle
                    offline -> "Offline mode"
                    else -> "You're online"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (mode == ConnectionMode.Automatic) "Follows your internet connection"
                else "Manual override for this demo session",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Explains how to leave offline mode without implying that a preview follows device radios. */
@Composable
private fun ReconnectHint(mode: ConnectionMode) {
    Text(
        if (mode == ConnectionMode.ForceOffline)
            "Offline preview stays on until you change Connection preview in Profile or Manage. Your drafts and pending payments stay here."
        else
            "Reconnect to Wi-Fi or mobile data. Colours return automatically; your drafts and pending payments stay here.",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted,
    )
}
