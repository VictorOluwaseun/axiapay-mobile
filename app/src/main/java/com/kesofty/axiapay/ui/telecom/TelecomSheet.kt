package com.kesofty.axiapay.ui.telecom

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
import com.kesofty.axiapay.domain.telecom.*
import com.kesofty.axiapay.presentation.telecom.*
import com.kesofty.axiapay.ui.SheetContent
import com.kesofty.axiapay.ui.SheetHeading
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*
import com.kesofty.axiapay.ui.walletMoney
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Render live account-owned airtime/data state in the existing payment sheet; only typed intents
 * can trigger operations.
 */
@Composable
fun TelecomSheet(
    state: TelecomUiState,
    offline: Boolean,
    balanceHidden: Boolean,
    onIntent: (TelecomIntent) -> Unit,
    onOpenAccount: () -> Unit,
) {
    SheetContent {
        IconTile(Glyph.Phone, tint = Mint)
        SheetHeading(
            stringResource(
                if (state.service == TelecomService.Data) R.string.telecom_title_data
                else R.string.telecom_title_airtime,
            ),
            stringResource(R.string.telecom_subtitle),
        )
        if (offline) {
            Text(stringResource(R.string.telecom_offline), color = Warning)
        }
        if (state.isProcessing) {
            Text(stringResource(R.string.telecom_processing), color = Pine)
            LinearProgressIndicator(
                Modifier.fillMaxWidth().testTag("telecom-processing"),
                color = Pine,
                trackColor = Mint,
            )
        }
        state.issue?.let { Notice(it) }
        when (state.screen) {
            TelecomScreen.Hidden,
            TelecomScreen.Loading -> {
                Text(stringResource(R.string.telecom_loading), Modifier.testTag("telecom-loading"))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pine, trackColor = Mint)
            }
            TelecomScreen.SignIn -> {
                Text(stringResource(R.string.telecom_sign_in), Modifier.testTag("telecom-sign-in"))
                PrimaryButton(
                    stringResource(R.string.telecom_sign_in_button),
                    enabled = !state.isProcessing,
                    onClick = onOpenAccount,
                )
            }
            TelecomScreen.LoadFailure ->
                PrimaryButton(
                    stringResource(R.string.telecom_retry_load),
                    Modifier.testTag("telecom-reload"),
                    !state.isProcessing,
                ) {
                    onIntent(TelecomIntent.Reload)
                }
            TelecomScreen.Form -> TelecomFields(state, offline, balanceHidden, onIntent)
            TelecomScreen.Review -> TelecomReview(state, offline, onIntent)
            TelecomScreen.History -> TelecomHistory(state, onIntent)
            TelecomScreen.Progress -> TelecomProgress(state, offline, onIntent)
        }
    }
}

/**
 * Display API catalogue selection and exact input feedback without local provider IDs, prices or
 * demo balances.
 */
@Composable
private fun ColumnScope.TelecomFields(
    state: TelecomUiState,
    offline: Boolean,
    hidden: Boolean,
    onIntent: (TelecomIntent) -> Unit,
) {
    val context = state.context ?: return
    val wallet = context.wallet
    if (wallet == null) {
        PrimaryButton(stringResource(R.string.telecom_retry_load), enabled = !state.isProcessing) {
            onIntent(TelecomIntent.Reload)
        }
        SavedPaymentsLink(state, onIntent)
        return
    }
    InfoRow(
        stringResource(R.string.telecom_wallet_balance),
        if (hidden) stringResource(R.string.telecom_balance_hidden)
        else walletMoney(wallet.availableMinorUnits, wallet.currency),
    )
    Text(stringResource(R.string.telecom_network), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.telecom_network_help),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    if (state.service == TelecomService.Data) {
        DataNetworks(state, onIntent)
        Text(stringResource(R.string.telecom_plan), style = MaterialTheme.typography.titleSmall)
    }
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        state.visibleProducts.forEach { product ->
            Surface(
                Modifier.then(
                        if (state.service == TelecomService.Data) Modifier.fillMaxWidth()
                        else Modifier,
                    )
                    .testTag("telecom-product-${product.id}")
                    .selectable(
                        product.id == state.selectedProductId,
                        enabled = !state.isProcessing,
                        role = Role.RadioButton,
                    ) {
                        onIntent(TelecomIntent.SelectProduct(product.id))
                    },
                shape = RoundedCornerShape(14.dp),
                color = if (product.id == state.selectedProductId) Mint else Canvas,
                border = controlBorder(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(product.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (state.service == TelecomService.Data) {
                        DataPlanSummary(product)
                    }
                    if (!product.isSubmissionAvailable) {
                        Text(
                            stringResource(R.string.telecom_error_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
            }
        }
    }
    if (context.products.isEmpty()) {
        Text(stringResource(R.string.telecom_empty), Modifier.testTag("telecom-empty"))
        TextButton({ onIntent(TelecomIntent.Reload) }, enabled = !state.isProcessing) {
            Text(stringResource(R.string.telecom_retry_load))
        }
    }
    OutlinedTextField(
        state.recipient,
        { onIntent(TelecomIntent.EditRecipient(it)) },
        Modifier.fillMaxWidth().testTag("telecom-recipient"),
        enabled = !state.isProcessing,
        label = { Text(stringResource(R.string.telecom_phone)) },
        singleLine = true,
        supportingText = { Text(stringResource(R.string.telecom_phone_help)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    val product = state.selectedProduct
    val fixed = product?.fixedMinorUnits
    when {
        product == null && state.service == TelecomService.Data ->
            Text(stringResource(R.string.telecom_choose_plan))
        fixed == null ->
            OutlinedTextField(
                state.amount,
                { onIntent(TelecomIntent.EditAmount(it)) },
                Modifier.fillMaxWidth().testTag("telecom-amount"),
                enabled = !state.isProcessing,
                label = { Text(stringResource(R.string.telecom_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        else ->
            Text(
                walletMoney(fixed, product.currency),
                style = MaterialTheme.typography.headlineMedium,
            )
    }
    product
        ?.takeIf { it.fixedMinorUnits == null }
        ?.let {
            Text(
                stringResource(
                    R.string.telecom_limits,
                    walletMoney(it.minimumMinorUnits, it.currency),
                    walletMoney(it.maximumMinorUnits, it.currency),
                    walletMoney(it.stepMinorUnits, it.currency),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    if (
        state.inputIssue != null &&
            state.recipient.isNotBlank() &&
            (product?.fixedMinorUnits != null || state.amount.isNotBlank())
    ) {
        Notice(state.inputIssue)
    }
    PrimaryButton(
        stringResource(R.string.telecom_review),
        Modifier.testTag("telecom-review"),
        state.prepared != null && !state.isProcessing && !offline,
    ) {
        onIntent(TelecomIntent.Review)
    }
    SavedPaymentsLink(state, onIntent)
}

/** Show immutable server terms and expiry before any explicit wallet authorization. */
@Composable
private fun ColumnScope.TelecomReview(
    state: TelecomUiState,
    offline: Boolean,
    onIntent: (TelecomIntent) -> Unit,
) {
    val review = state.review ?: return
    val quote = review.quote
    Text(
        stringResource(
            if (state.service == TelecomService.Data) R.string.telecom_review_title_data
            else R.string.telecom_review_title_airtime,
        ),
        style = MaterialTheme.typography.titleLarge,
    )
    QuoteTerms(quote)
    Text(
        pluralStringResource(
            R.plurals.telecom_seconds,
            state.secondsRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            state.secondsRemaining,
        ),
        Modifier.testTag("telecom-expiry"),
        color = Muted,
    )
    if (state.secondsRemaining == 0L) {
        Notice(TelecomIssue.QuoteExpired)
    }
    if (!quote.isSubmissionAvailable) {
        Notice(TelecomIssue.ProductUnavailable)
    }
    Text(stringResource(R.string.telecom_consent), style = MaterialTheme.typography.bodyMedium)
    PrimaryButton(
        stringResource(
            R.string.telecom_pay,
            walletMoney(quote.totalDebitMinorUnits, quote.currency),
        ),
        Modifier.testTag("telecom-confirm"),
        state.secondsRemaining > 0L &&
            quote.isSubmissionAvailable &&
            !state.isProcessing &&
            !offline,
    ) {
        onIntent(TelecomIntent.Confirm)
    }
    TextButton({ onIntent(TelecomIntent.Edit) }, enabled = !state.isProcessing) {
        Text(stringResource(R.string.telecom_edit))
    }
    SavedPaymentsLink(state, onIntent)
}

/**
 * Saved references are owner-scoped; rendering the list never sends a purchase or a reconcile
 * request.
 */
@Composable
private fun ColumnScope.TelecomHistory(state: TelecomUiState, onIntent: (TelecomIntent) -> Unit) {
    val requests = state.context?.requests.orEmpty().asReversed()
    Text(stringResource(R.string.telecom_history), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.telecom_history_help),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    if (requests.isEmpty()) {
        Text(stringResource(R.string.telecom_history_empty))
    }
    requests.forEach { request ->
        OutlinedButton(
            { onIntent(TelecomIntent.CheckSaved(request.requestKey)) },
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("telecom-saved-${request.requestKey}"),
            enabled = !state.isProcessing,
        ) {
            Text(stringResource(R.string.telecom_check_saved, request.requestKey.take(8)))
        }
    }
    if (requests.any { !it.acknowledged }) {
        Text(stringResource(R.string.telecom_earlier_warning), color = Warning)
    }
    PrimaryButton(
        stringResource(R.string.telecom_new),
        Modifier.testTag("telecom-new"),
        !state.isProcessing,
    ) {
        onIntent(TelecomIntent.NewPurchase)
    }
}

/**
 * Final status comes only from committed API progress; pending/unconfirmed states retain safe
 * recovery actions.
 */
@Composable
private fun ColumnScope.TelecomProgress(
    state: TelecomUiState,
    offline: Boolean,
    onIntent: (TelecomIntent) -> Unit,
) {
    val progress = state.progress ?: return
    val status = progress.payment?.status
    Text(
        stringResource(statusTitle(status, state.service)),
        Modifier.testTag("telecom-status").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.headlineMedium,
        color = if (status == TelecomPaymentStatus.Failed) Danger else Pine,
    )
    Text(stringResource(statusHelp(status)), style = MaterialTheme.typography.bodyMedium)
    progress.issue?.let { Notice(it) }
    progress.quote?.let { QuoteTerms(it) }
    Text(
        stringResource(R.string.telecom_request_reference, progress.request.requestKey),
        Modifier.testTag("telecom-request-key"),
        style = MaterialTheme.typography.bodySmall,
    )
    progress.payment?.let {
        Text(
            stringResource(R.string.telecom_payment_reference, it.id),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (progress.payment?.isFinal == true) {
        PrimaryButton(
            stringResource(R.string.telecom_done),
            Modifier.testTag("telecom-done"),
            !state.isProcessing,
        ) {
            onIntent(TelecomIntent.Done)
        }
        return
    }
    PrimaryButton(
        stringResource(R.string.telecom_check),
        Modifier.testTag("telecom-check"),
        !state.isProcessing,
    ) {
        onIntent(TelecomIntent.CheckStatus)
    }
    when (status) {
        TelecomPaymentStatus.Pending,
        TelecomPaymentStatus.Reserved ->
            OutlinedButton(
                { onIntent(TelecomIntent.Reconcile) },
                Modifier.fillMaxWidth().testTag("telecom-reconcile"),
                enabled = !state.isProcessing && !offline,
            ) {
                Text(stringResource(R.string.telecom_reconcile))
            }
        null,
        TelecomPaymentStatus.AwaitingFunds ->
            OutlinedButton(
                { onIntent(TelecomIntent.RetrySubmission) },
                Modifier.fillMaxWidth().testTag("telecom-retry-original"),
                enabled = !state.isProcessing && !offline,
            ) {
                Text(
                    stringResource(
                        if (status == TelecomPaymentStatus.AwaitingFunds) R.string.telecom_resume
                        else R.string.telecom_retry_original,
                    ),
                )
            }
        TelecomPaymentStatus.Succeeded,
        TelecomPaymentStatus.Failed -> Unit
    }
    SavedPaymentsLink(state, onIntent)
}

/**
 * Render the server review's recipient and exact charges without reconstructing price or fee rules.
 */
@Composable
private fun QuoteTerms(quote: TelecomQuote) {
    Column(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfoRow(stringResource(R.string.telecom_network), quote.networkCode.uppercase(Locale.ROOT))
        InfoRow(stringResource(R.string.telecom_product), quote.displayName)
        if (quote.service == TelecomService.Data) {
            InfoRow(
                stringResource(R.string.telecom_allowance),
                quote.allowanceLabel ?: stringResource(R.string.telecom_not_provided),
            )
            InfoRow(stringResource(R.string.telecom_validity), planValidity(quote.validitySeconds))
        }
        InfoRow(stringResource(R.string.telecom_recipient), quote.recipientPhoneNumber)
        InfoRow(
            stringResource(R.string.telecom_principal),
            walletMoney(quote.principalMinorUnits, quote.currency),
        )
        InfoRow(
            stringResource(R.string.telecom_fee),
            walletMoney(quote.feeMinorUnits, quote.currency),
        )
        Text(
            stringResource(R.string.telecom_total) +
                ": " +
                walletMoney(quote.totalDebitMinorUnits, quote.currency),
            Modifier.testTag("telecom-total"),
            style = MaterialTheme.typography.titleMedium,
            color = Pine,
        )
        InfoRow(
            stringResource(R.string.telecom_expires),
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(quote.expiresAt),
        )
    }
}

/** Expose saved receipts and unfinished work without copying it into simulated Activity. */
@Composable
private fun ColumnScope.SavedPaymentsLink(
    state: TelecomUiState,
    onIntent: (TelecomIntent) -> Unit,
) {
    if (state.context?.requests.isNullOrEmpty()) {
        return
    }
    TextButton(
        { onIntent(TelecomIntent.History) },
        Modifier.testTag("telecom-history"),
        enabled = !state.isProcessing,
    ) {
        Text(stringResource(R.string.telecom_history))
    }
    if (state.context?.requests?.any { !it.acknowledged } == true) {
        Text(
            stringResource(R.string.telecom_earlier_warning),
            style = MaterialTheme.typography.bodySmall,
            color = Warning,
        )
    }
}

/**
 * Resolve only safe classifications to copy; raw API/provider error messages are never rendered.
 */
@Composable
private fun Notice(issue: TelecomIssue) {
    Text(
        stringResource(issueMessage(issue)),
        Modifier.testTag("telecom-error").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = Warning,
    )
}

/** Keep final and non-final status labels explicit and exhaustive. */
@StringRes
private fun statusTitle(status: TelecomPaymentStatus?, service: TelecomService): Int =
    when (status) {
        TelecomPaymentStatus.Succeeded ->
            if (service == TelecomService.Data) R.string.telecom_status_success_data
            else R.string.telecom_status_success_airtime
        TelecomPaymentStatus.Failed ->
            if (service == TelecomService.Data) R.string.telecom_status_failed_data
            else R.string.telecom_status_failed_airtime
        TelecomPaymentStatus.Pending -> R.string.telecom_status_pending
        TelecomPaymentStatus.Reserved -> R.string.telecom_status_reserved
        TelecomPaymentStatus.AwaitingFunds -> R.string.telecom_status_funds
        null -> R.string.telecom_status_unknown
    }

/** Explain recovery without implying that an acknowledgement or timeout establishes delivery. */
@StringRes
private fun statusHelp(status: TelecomPaymentStatus?): Int =
    when (status) {
        TelecomPaymentStatus.Succeeded -> R.string.telecom_success_help
        TelecomPaymentStatus.Failed -> R.string.telecom_failed_help
        TelecomPaymentStatus.AwaitingFunds -> R.string.telecom_funds_help
        TelecomPaymentStatus.Pending,
        TelecomPaymentStatus.Reserved -> R.string.telecom_pending_help
        null -> R.string.telecom_unknown_help
    }

/**
 * Map auth, storage, financial-rejection and uncertain transport failures to separate recovery
 * advice.
 */
@StringRes
private fun issueMessage(issue: TelecomIssue): Int =
    when (issue) {
        TelecomIssue.SignInRequired,
        TelecomIssue.SessionChanged -> R.string.telecom_error_session
        TelecomIssue.Configuration -> R.string.telecom_error_configuration
        TelecomIssue.WalletMissing,
        TelecomIssue.WalletUnavailable,
        TelecomIssue.WalletRestricted -> R.string.telecom_error_wallet
        TelecomIssue.Network -> R.string.telecom_error_network
        TelecomIssue.Unavailable -> R.string.telecom_error_unavailable
        TelecomIssue.InvalidResponse -> R.string.telecom_error_response
        TelecomIssue.SelectProduct,
        TelecomIssue.ProductUnavailable -> R.string.telecom_error_product
        TelecomIssue.InvalidRecipient -> R.string.telecom_error_phone
        TelecomIssue.InvalidAmount -> R.string.telecom_error_amount
        TelecomIssue.QuoteExpired -> R.string.telecom_error_expired
        TelecomIssue.PaymentConflict -> R.string.telecom_error_conflict
        TelecomIssue.InsufficientFunds -> R.string.telecom_error_funds
        TelecomIssue.Storage -> R.string.telecom_error_storage
        TelecomIssue.NotFound -> R.string.telecom_error_not_found
    }

/** Choose an API network explicitly; changing it clears the previous data plan in the owner. */
@Composable
private fun ColumnScope.DataNetworks(state: TelecomUiState, onIntent: (TelecomIntent) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.networks.forEach { network ->
            Surface(
                Modifier.testTag("telecom-network-$network").selectable(
                    network == state.selectedNetworkCode,
                    enabled = !state.isProcessing,
                    role = Role.RadioButton,
                ) {
                    onIntent(TelecomIntent.SelectNetwork(network))
                },
                color = if (network == state.selectedNetworkCode) Mint else Canvas,
                shape = RoundedCornerShape(14.dp),
                border = controlBorder(),
            ) {
                Text(
                    network.uppercase(Locale.ROOT),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
    if (state.selectedNetworkCode == null) {
        Text(stringResource(R.string.telecom_choose_network), color = Muted)
    }
    if (state.selectedNetworkCode != null && state.visibleProducts.isEmpty()) {
        Text(stringResource(R.string.telecom_no_plans), color = Muted)
    }
}

/**
 * Display only published plan terms; omitted metadata never acquires a sample allowance/validity.
 */
@Composable
private fun DataPlanSummary(product: TelecomProduct) {
    product.fixedMinorUnits?.let {
        Text(
            stringResource(R.string.telecom_plan_price, walletMoney(it, product.currency)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        stringResource(
            R.string.telecom_plan_allowance,
            product.allowanceLabel ?: stringResource(R.string.telecom_not_provided),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    Text(
        stringResource(R.string.telecom_plan_validity, planValidity(product.validitySeconds)),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
}

/** Format an exact published duration in its largest whole unit, distinct from quote expiry. */
@Composable
private fun planValidity(seconds: Long?): String {
    if (seconds == null) {
        return stringResource(R.string.telecom_not_provided)
    }
    val (resource, count) =
        when {
            seconds % 86400L == 0L -> R.plurals.telecom_validity_days to seconds / 86400L
            seconds % 3600L == 0L -> R.plurals.telecom_validity_hours to seconds / 3600L
            seconds % 60L == 0L -> R.plurals.telecom_validity_minutes to seconds / 60L
            else -> R.plurals.telecom_validity_seconds to seconds
        }
    return pluralStringResource(resource, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count)
}
