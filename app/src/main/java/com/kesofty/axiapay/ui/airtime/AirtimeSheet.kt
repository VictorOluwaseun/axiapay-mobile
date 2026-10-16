package com.kesofty.axiapay.ui.airtime

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.airtime.*
import com.kesofty.axiapay.presentation.airtime.*
import com.kesofty.axiapay.ui.SheetContent
import com.kesofty.axiapay.ui.SheetHeading
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.ui.walletMoney
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Render live account-owned airtime state in the existing payment sheet; only typed intents can
 * trigger operations.
 */
@Composable
fun AirtimeSheet(
    state: AirtimeUiState,
    offline: Boolean,
    balanceHidden: Boolean,
    onIntent: (AirtimeIntent) -> Unit,
    onOpenAccount: () -> Unit,
) {
    SheetContent {
        IconTile(Glyph.Phone, tint = Mint)
        SheetHeading(
            stringResource(R.string.airtime_title),
            stringResource(R.string.airtime_subtitle),
        )
        if (offline) {
            Text(stringResource(R.string.airtime_offline), color = Warning)
        }
        if (state.isProcessing) {
            Text(stringResource(R.string.airtime_processing), color = Pine)
            LinearProgressIndicator(
                Modifier.fillMaxWidth().testTag("airtime-processing"),
                color = Pine,
                trackColor = Mint,
            )
        }
        state.issue?.let { Notice(it) }
        when (state.screen) {
            AirtimeScreen.Hidden,
            AirtimeScreen.Loading -> {
                Text(stringResource(R.string.airtime_loading), Modifier.testTag("airtime-loading"))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pine, trackColor = Mint)
            }
            AirtimeScreen.SignIn -> {
                Text(stringResource(R.string.airtime_sign_in), Modifier.testTag("airtime-sign-in"))
                PrimaryButton(
                    stringResource(R.string.airtime_sign_in_button),
                    enabled = !state.isProcessing,
                    onClick = onOpenAccount,
                )
            }
            AirtimeScreen.LoadFailure ->
                PrimaryButton(
                    stringResource(R.string.airtime_retry_load),
                    Modifier.testTag("airtime-reload"),
                    !state.isProcessing,
                ) {
                    onIntent(AirtimeIntent.Reload)
                }
            AirtimeScreen.Form -> AirtimeFields(state, offline, balanceHidden, onIntent)
            AirtimeScreen.Review -> AirtimeReview(state, offline, onIntent)
            AirtimeScreen.History -> AirtimeHistory(state, onIntent)
            AirtimeScreen.Progress -> AirtimeProgress(state, offline, onIntent)
        }
    }
}

/**
 * Display API catalogue selection and exact input feedback without local provider IDs, prices or
 * demo balances.
 */
@Composable
private fun ColumnScope.AirtimeFields(
    state: AirtimeUiState,
    offline: Boolean,
    hidden: Boolean,
    onIntent: (AirtimeIntent) -> Unit,
) {
    val context = state.context ?: return
    val wallet = context.wallet
    if (wallet == null) {
        PrimaryButton(stringResource(R.string.airtime_retry_load), enabled = !state.isProcessing) {
            onIntent(AirtimeIntent.Reload)
        }
        SavedPaymentsLink(state, onIntent)
        return
    }
    InfoRow(
        stringResource(R.string.airtime_wallet_balance),
        if (hidden) stringResource(R.string.airtime_balance_hidden)
        else walletMoney(wallet.availableMinorUnits, wallet.currency),
    )
    Text(stringResource(R.string.airtime_network), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.airtime_network_help),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        context.products.forEach { product ->
            Surface(
                Modifier.testTag("airtime-product-${product.id}").selectable(
                    product.id == state.selectedProductId,
                    enabled = !state.isProcessing,
                    role = Role.RadioButton,
                ) {
                    onIntent(AirtimeIntent.SelectProduct(product.id))
                },
                shape = RoundedCornerShape(14.dp),
                color = if (product.id == state.selectedProductId) Mint else Canvas,
                border = controlBorder(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(product.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (!product.isSubmissionAvailable) {
                        Text(
                            stringResource(R.string.airtime_error_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
            }
        }
    }
    if (context.products.isEmpty()) {
        Text(stringResource(R.string.airtime_empty), Modifier.testTag("airtime-empty"))
        TextButton({ onIntent(AirtimeIntent.Reload) }, enabled = !state.isProcessing) {
            Text(stringResource(R.string.airtime_retry_load))
        }
    }
    OutlinedTextField(
        state.recipient,
        { onIntent(AirtimeIntent.EditRecipient(it)) },
        Modifier.fillMaxWidth().testTag("airtime-recipient"),
        enabled = !state.isProcessing,
        label = { Text(stringResource(R.string.airtime_phone)) },
        singleLine = true,
        supportingText = { Text(stringResource(R.string.airtime_phone_help)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    val product = state.selectedProduct
    when (val fixed = product?.fixedMinorUnits) {
        null ->
            OutlinedTextField(
                state.amount,
                { onIntent(AirtimeIntent.EditAmount(it)) },
                Modifier.fillMaxWidth().testTag("airtime-amount"),
                enabled = !state.isProcessing,
                label = { Text(stringResource(R.string.airtime_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        else ->
            Text(
                walletMoney(fixed, product.currency),
                style = MaterialTheme.typography.headlineMedium,
            )
    }
    product?.let {
        Text(
            stringResource(
                R.string.airtime_limits,
                walletMoney(it.minimumMinorUnits, it.currency),
                walletMoney(it.maximumMinorUnits, it.currency),
                walletMoney(it.stepMinorUnits, it.currency),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
    if (state.inputIssue != null && state.recipient.isNotBlank() && state.amount.isNotBlank()) {
        Notice(state.inputIssue)
    }
    PrimaryButton(
        stringResource(R.string.airtime_review),
        Modifier.testTag("airtime-review"),
        state.prepared != null && !state.isProcessing && !offline,
    ) {
        onIntent(AirtimeIntent.Review)
    }
    SavedPaymentsLink(state, onIntent)
}

/** Show immutable server terms and expiry before any explicit wallet authorization. */
@Composable
private fun ColumnScope.AirtimeReview(
    state: AirtimeUiState,
    offline: Boolean,
    onIntent: (AirtimeIntent) -> Unit,
) {
    val review = state.review ?: return
    val quote = review.quote
    Text(stringResource(R.string.airtime_review_title), style = MaterialTheme.typography.titleLarge)
    QuoteTerms(quote)
    Text(
        pluralStringResource(
            R.plurals.airtime_seconds,
            state.secondsRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            state.secondsRemaining,
        ),
        Modifier.testTag("airtime-expiry"),
        color = Muted,
    )
    if (state.secondsRemaining == 0L) {
        Notice(AirtimeIssue.QuoteExpired)
    }
    if (!quote.isSubmissionAvailable) {
        Notice(AirtimeIssue.ProductUnavailable)
    }
    Text(stringResource(R.string.airtime_consent), style = MaterialTheme.typography.bodyMedium)
    PrimaryButton(
        stringResource(
            R.string.airtime_pay,
            walletMoney(quote.totalDebitMinorUnits, quote.currency),
        ),
        Modifier.testTag("airtime-confirm"),
        state.secondsRemaining > 0L &&
            quote.isSubmissionAvailable &&
            !state.isProcessing &&
            !offline,
    ) {
        onIntent(AirtimeIntent.Confirm)
    }
    TextButton({ onIntent(AirtimeIntent.Edit) }, enabled = !state.isProcessing) {
        Text(stringResource(R.string.airtime_edit))
    }
    SavedPaymentsLink(state, onIntent)
}

/**
 * Saved references are owner-scoped; rendering the list never sends a purchase or a reconcile
 * request.
 */
@Composable
private fun ColumnScope.AirtimeHistory(state: AirtimeUiState, onIntent: (AirtimeIntent) -> Unit) {
    val requests = state.context?.requests.orEmpty().asReversed()
    Text(stringResource(R.string.airtime_history), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.airtime_history_help),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    if (requests.isEmpty()) {
        Text(stringResource(R.string.airtime_history_empty))
    }
    requests.forEach { request ->
        OutlinedButton(
            { onIntent(AirtimeIntent.CheckSaved(request.requestKey)) },
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("airtime-saved-${request.requestKey}"),
            enabled = !state.isProcessing,
        ) {
            Text(stringResource(R.string.airtime_check_saved, request.requestKey.take(8)))
        }
    }
    if (requests.any { !it.acknowledged }) {
        Text(stringResource(R.string.airtime_earlier_warning), color = Warning)
    }
    PrimaryButton(
        stringResource(R.string.airtime_new),
        Modifier.testTag("airtime-new"),
        !state.isProcessing,
    ) {
        onIntent(AirtimeIntent.NewPurchase)
    }
}

/**
 * Final status comes only from committed API progress; pending/unconfirmed states retain safe
 * recovery actions.
 */
@Composable
private fun ColumnScope.AirtimeProgress(
    state: AirtimeUiState,
    offline: Boolean,
    onIntent: (AirtimeIntent) -> Unit,
) {
    val progress = state.progress ?: return
    val status = progress.payment?.status
    Text(
        stringResource(statusTitle(status)),
        Modifier.testTag("airtime-status").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.headlineMedium,
        color = if (status == AirtimePaymentStatus.Failed) Danger else Pine,
    )
    Text(stringResource(statusHelp(status)), style = MaterialTheme.typography.bodyMedium)
    progress.issue?.let { Notice(it) }
    progress.quote?.let { QuoteTerms(it) }
    Text(
        stringResource(R.string.airtime_request_reference, progress.request.requestKey),
        Modifier.testTag("airtime-request-key"),
        style = MaterialTheme.typography.bodySmall,
    )
    progress.payment?.let {
        Text(
            stringResource(R.string.airtime_payment_reference, it.id),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (progress.payment?.isFinal == true) {
        PrimaryButton(
            stringResource(R.string.airtime_done),
            Modifier.testTag("airtime-done"),
            !state.isProcessing,
        ) {
            onIntent(AirtimeIntent.Done)
        }
        return
    }
    PrimaryButton(
        stringResource(R.string.airtime_check),
        Modifier.testTag("airtime-check"),
        !state.isProcessing,
    ) {
        onIntent(AirtimeIntent.CheckStatus)
    }
    when (status) {
        AirtimePaymentStatus.Pending,
        AirtimePaymentStatus.Reserved ->
            OutlinedButton(
                { onIntent(AirtimeIntent.Reconcile) },
                Modifier.fillMaxWidth().testTag("airtime-reconcile"),
                enabled = !state.isProcessing && !offline,
            ) {
                Text(stringResource(R.string.airtime_reconcile))
            }
        null,
        AirtimePaymentStatus.AwaitingFunds ->
            OutlinedButton(
                { onIntent(AirtimeIntent.RetrySubmission) },
                Modifier.fillMaxWidth().testTag("airtime-retry-original"),
                enabled = !state.isProcessing && !offline,
            ) {
                Text(
                    stringResource(
                        if (status == AirtimePaymentStatus.AwaitingFunds) R.string.airtime_resume
                        else R.string.airtime_retry_original,
                    ),
                )
            }
        AirtimePaymentStatus.Succeeded,
        AirtimePaymentStatus.Failed -> Unit
    }
    SavedPaymentsLink(state, onIntent)
}

/**
 * Render the server review's recipient and exact charges without reconstructing price or fee rules.
 */
@Composable
private fun QuoteTerms(quote: AirtimeQuote) {
    Column(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfoRow(stringResource(R.string.airtime_product), quote.displayName)
        InfoRow(stringResource(R.string.airtime_recipient), quote.recipientPhoneNumber)
        InfoRow(
            stringResource(R.string.airtime_principal),
            walletMoney(quote.principalMinorUnits, quote.currency),
        )
        InfoRow(
            stringResource(R.string.airtime_fee),
            walletMoney(quote.feeMinorUnits, quote.currency),
        )
        Text(
            stringResource(R.string.airtime_total) +
                ": " +
                walletMoney(quote.totalDebitMinorUnits, quote.currency),
            Modifier.testTag("airtime-total"),
            style = MaterialTheme.typography.titleMedium,
            color = Pine,
        )
        InfoRow(
            stringResource(R.string.airtime_expires),
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(quote.expiresAt),
        )
    }
}

/** Expose saved receipts and unfinished work without copying it into simulated Activity. */
@Composable
private fun ColumnScope.SavedPaymentsLink(
    state: AirtimeUiState,
    onIntent: (AirtimeIntent) -> Unit,
) {
    if (state.context?.requests.isNullOrEmpty()) {
        return
    }
    TextButton(
        { onIntent(AirtimeIntent.History) },
        Modifier.testTag("airtime-history"),
        enabled = !state.isProcessing,
    ) {
        Text(stringResource(R.string.airtime_history))
    }
    if (state.context?.requests?.any { !it.acknowledged } == true) {
        Text(
            stringResource(R.string.airtime_earlier_warning),
            style = MaterialTheme.typography.bodySmall,
            color = Warning,
        )
    }
}

/**
 * Resolve only safe classifications to copy; raw API/provider error messages are never rendered.
 */
@Composable
private fun Notice(issue: AirtimeIssue) {
    Text(
        stringResource(issueMessage(issue)),
        Modifier.testTag("airtime-error").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = Warning,
    )
}

/** Keep final and non-final status labels explicit and exhaustive. */
@StringRes
private fun statusTitle(status: AirtimePaymentStatus?): Int =
    when (status) {
        AirtimePaymentStatus.Succeeded -> R.string.airtime_status_success
        AirtimePaymentStatus.Failed -> R.string.airtime_status_failed
        AirtimePaymentStatus.Pending -> R.string.airtime_status_pending
        AirtimePaymentStatus.Reserved -> R.string.airtime_status_reserved
        AirtimePaymentStatus.AwaitingFunds -> R.string.airtime_status_funds
        null -> R.string.airtime_status_unknown
    }

/** Explain recovery without implying that an acknowledgement or timeout establishes delivery. */
@StringRes
private fun statusHelp(status: AirtimePaymentStatus?): Int =
    when (status) {
        AirtimePaymentStatus.Succeeded -> R.string.airtime_success_help
        AirtimePaymentStatus.Failed -> R.string.airtime_failed_help
        AirtimePaymentStatus.AwaitingFunds -> R.string.airtime_funds_help
        AirtimePaymentStatus.Pending,
        AirtimePaymentStatus.Reserved -> R.string.airtime_pending_help
        null -> R.string.airtime_unknown_help
    }

/**
 * Map auth, storage, financial-rejection and uncertain transport failures to separate recovery
 * advice.
 */
@StringRes
private fun issueMessage(issue: AirtimeIssue): Int =
    when (issue) {
        AirtimeIssue.SignInRequired,
        AirtimeIssue.SessionChanged -> R.string.airtime_error_session
        AirtimeIssue.Configuration -> R.string.airtime_error_configuration
        AirtimeIssue.WalletMissing,
        AirtimeIssue.WalletUnavailable,
        AirtimeIssue.WalletRestricted -> R.string.airtime_error_wallet
        AirtimeIssue.Network -> R.string.airtime_error_network
        AirtimeIssue.Unavailable -> R.string.airtime_error_unavailable
        AirtimeIssue.InvalidResponse -> R.string.airtime_error_response
        AirtimeIssue.SelectProduct,
        AirtimeIssue.ProductUnavailable -> R.string.airtime_error_product
        AirtimeIssue.InvalidRecipient -> R.string.airtime_error_phone
        AirtimeIssue.InvalidAmount -> R.string.airtime_error_amount
        AirtimeIssue.QuoteExpired -> R.string.airtime_error_expired
        AirtimeIssue.PaymentConflict -> R.string.airtime_error_conflict
        AirtimeIssue.InsufficientFunds -> R.string.airtime_error_funds
        AirtimeIssue.Storage -> R.string.airtime_error_storage
        AirtimeIssue.NotFound -> R.string.airtime_error_not_found
    }
