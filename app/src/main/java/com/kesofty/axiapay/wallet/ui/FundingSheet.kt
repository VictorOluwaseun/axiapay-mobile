package com.kesofty.axiapay.wallet.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.wallet.domain.*
import com.kesofty.axiapay.wallet.presentation.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Render server progress and copyable bank instructions; app handoffs are explicit clicks, never
 * confirmation.
 */
@Composable
fun FundingSheet(state: FundingUiState, offline: Boolean, emit: (FundingIntent) -> Unit) {
    val enabled = !state.busy && !offline
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.funding_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
        )
        if (offline) {
            Text(stringResource(R.string.funding_offline), color = Muted)
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.issue?.let { Text(stringResource(fundingIssueText(it)), color = Danger) }
        if (state.request == null) {
            FundingEntry(state, enabled, emit)
        } else {
            FundingProgress(state.request, state, enabled, emit)
        }
        OutlinedButton(
            { emit(FundingIntent.Refresh) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.funding_refresh))
        }
        Text(
            stringResource(R.string.funding_history),
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
        )
        state.context?.requests?.forEach { request ->
            OutlinedButton(
                { emit(FundingIntent.Select(request.id)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(fundingMoney(request))
                    Text(
                        stringResource(fundingStatusText(request.status)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.offset > 0) {
                TextButton(
                    { emit(FundingIntent.Page((state.offset - 25).coerceAtLeast(0))) },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.funding_previous))
                }
            }
            state.context?.nextOffset?.let { offset ->
                TextButton({ emit(FundingIntent.Page(offset)) }, enabled = enabled) {
                    Text(stringResource(R.string.funding_next))
                }
            }
        }
        Text(
            stringResource(R.string.funding_outside_request),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

/**
 * The selected capability determines required entry fields; draft money is prepared by the
 * application use case.
 */
@Composable
private fun FundingEntry(state: FundingUiState, enabled: Boolean, emit: (FundingIntent) -> Unit) {
    val options = state.context?.options.orEmpty()
    if (options.isEmpty() && !state.busy) {
        Text(stringResource(R.string.funding_unavailable), color = Muted)
        return
    }
    options.forEach { option ->
        FilterChip(
            selected = state.optionId == option.id,
            onClick = { emit(FundingIntent.Option(option.id)) },
            enabled = enabled && !state.submissionUncertain,
            label = { Text(option.name) },
        )
    }
    OutlinedTextField(
        state.amount,
        { emit(FundingIntent.Amount(it)) },
        label = { Text(stringResource(R.string.funding_amount)) },
        enabled = enabled && !state.submissionUncertain,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (options.find { it.id == state.optionId }?.bankTransfer == false) {
        OutlinedTextField(
            state.email,
            { emit(FundingIntent.Email(it)) },
            label = { Text(stringResource(R.string.funding_email)) },
            enabled = enabled && !state.submissionUncertain,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (state.submissionUncertain) {
        Text(stringResource(R.string.funding_uncertain), color = Muted)
    }
    Button(
        { emit(FundingIntent.Create) },
        enabled = enabled && options.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state.submissionUncertain) R.string.funding_retry else R.string.funding_create,
            ),
        )
    }
}

/**
 * Show the immutable destination and reference matching step without any customer-facing success
 * checkbox.
 */
@Composable
private fun FundingProgress(
    request: FundingRequest,
    state: FundingUiState,
    enabled: Boolean,
    emit: (FundingIntent) -> Unit,
) {
    val context = LocalContext.current
    Text(fundingMoney(request), style = MaterialTheme.typography.headlineLarge, color = Pine)
    Text(
        stringResource(fundingStatusText(request.status)),
        style = MaterialTheme.typography.titleMedium,
        color = Ink,
    )
    Text(
        stringResource(R.string.funding_request_id, request.id),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    val account = request.account
    if (request.status == FundingStatus.AwaitingTransfer && account != null) {
        Text(stringResource(R.string.funding_instructions), color = Muted)
        Surface(color = Canvas, shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(account.bankName, color = Ink)
                Text(account.accountName, color = Ink)
                Text(
                    account.accountNumber,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Pine,
                )
                OutlinedButton(
                    { copyFundingAccount(context, account.accountNumber) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.funding_copy))
                }
            }
        }
        OutlinedButton(
            { emit(FundingIntent.OpenOpay) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.funding_open_opay))
        }
        Text(
            stringResource(R.string.funding_opay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        OutlinedTextField(
            state.reference,
            { emit(FundingIntent.Reference(it)) },
            label = { Text(stringResource(R.string.funding_reference)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            { emit(FundingIntent.SubmitReference) },
            enabled = enabled && state.reference.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.funding_submit_reference))
        }
    }
    if (request.status == FundingStatus.Pending && request.checkoutUrl != null) {
        Button(
            { emit(FundingIntent.OpenCheckout) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.funding_open_checkout))
        }
    }
    request.customerReference?.let {
        Text(stringResource(R.string.funding_saved_reference, it), color = Muted)
    }
    request.reviewNote?.let { Text(it, color = Muted) }
    if (
        request.status == FundingStatus.AwaitingConfirmation ||
            request.status == FundingStatus.AwaitingCredit
    ) {
        Text(stringResource(R.string.funding_review_pending), color = Muted)
    }
    TextButton({ emit(FundingIntent.NewRequest) }, enabled = enabled) {
        Text(stringResource(R.string.funding_new))
    }
}

/** Map exhaustive server state to localized, truthful customer progress. */
private fun fundingStatusText(status: FundingStatus): Int =
    when (status) {
        FundingStatus.Pending -> R.string.funding_pending
        FundingStatus.AwaitingTransfer -> R.string.funding_awaiting_transfer
        FundingStatus.AwaitingConfirmation -> R.string.funding_awaiting_confirmation
        FundingStatus.AwaitingCredit -> R.string.funding_awaiting_credit
        FundingStatus.Rejected -> R.string.funding_rejected
        FundingStatus.Credited -> R.string.funding_credited
    }

/** Keep error copy safe and distinguish invalid input from uncertain outcomes. */
private fun fundingIssueText(issue: FundingIssue): Int =
    when (issue) {
        FundingIssue.InvalidInput -> R.string.funding_invalid_input
        FundingIssue.SignInRequired,
        FundingIssue.SessionChanged -> R.string.funding_sign_in
        FundingIssue.Conflict -> R.string.funding_conflict
        else -> R.string.funding_error
    }

/** Format exact minor units with the user's locale and explicit currency. */
private fun fundingMoney(request: FundingRequest): String =
    NumberFormat.getCurrencyInstance(Locale.getDefault())
        .apply { currency = Currency.getInstance(request.currency) }
        .format(BigDecimal(request.amountMinorUnits, 2))

/**
 * Copy only after an explicit tap; no sensitive evidence or authentication token enters the
 * clipboard.
 */
private fun copyFundingAccount(context: Context, number: String) {
    context
        .getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.funding_account), number))
    Toast.makeText(context, R.string.funding_copied, Toast.LENGTH_SHORT).show()
}

/**
 * Open the official app's launcher or a verified hosted URL; neither action carries a success
 * signal or prefills a transfer.
 */
fun launchFundingHandoff(context: Context, effect: FundingEffect) {
    try {
        val intent =
            when (effect.kind) {
                FundingEffect.Kind.OpenOpay ->
                    context.packageManager.getLaunchIntentForPackage("team.opay.pay")
                FundingEffect.Kind.OpenCheckout ->
                    Intent(Intent.ACTION_VIEW, Uri.parse(requireNotNull(effect.url)))
                FundingEffect.Kind.RefreshWallet -> return
            }
        if (intent == null) {
            Toast.makeText(context, R.string.funding_opay_missing, Toast.LENGTH_LONG).show()
            return
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.funding_opay_missing, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.funding_opay_missing, Toast.LENGTH_LONG).show()
    }
}
