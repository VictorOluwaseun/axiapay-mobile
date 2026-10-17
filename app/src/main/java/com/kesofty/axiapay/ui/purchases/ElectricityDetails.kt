package com.kesofty.axiapay.ui.purchases

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.purchases.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*

/** Explicit meter verification is non-spending and distinct from its service delivery contact. */
@Composable
internal fun ColumnScope.ElectricityVerification(
    state: PurchaseUiState,
    offline: Boolean,
    onIntent: (PurchaseIntent) -> Unit,
) {
    PrimaryButton(
        stringResource(R.string.electricity_live_verify),
        Modifier.testTag("electricity-verify"),
        state.canVerifyMeter && !state.isProcessing && !offline,
    ) {
        onIntent(PurchaseIntent.VerifyMeter)
    }
    state.verifiedMeter?.let { MeterDetails(it.validation) }
    OutlinedTextField(
        state.contactPhoneNumber,
        { onIntent(PurchaseIntent.EditContactPhone(it)) },
        Modifier.fillMaxWidth().testTag("electricity-contact"),
        enabled = !state.isProcessing,
        label = { Text(stringResource(R.string.electricity_live_contact)) },
        singleLine = true,
        supportingText = { Text(stringResource(R.string.telecom_phone_help)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
}

/** Show the verified customer and product-owned mode before consenting to wallet spending. */
@Composable
internal fun MeterDetails(validation: MeterValidation) {
    InfoRow(stringResource(R.string.electricity_live_customer), validation.customerName)
    InfoRow(
        stringResource(R.string.electricity_meter_type),
        stringResource(electricityMeterTypeLabel(validation.meterType)),
    )
    validation.serviceAddress?.let {
        InfoRow(stringResource(R.string.electricity_live_address), it)
    }
    InfoRow(
        stringResource(R.string.electricity_live_verification_expiry),
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
            .withZone(java.time.ZoneId.systemDefault())
            .format(validation.expiresAt),
    )
}

/**
 * Render only authenticated fulfilment; delayed tokens retain the original payment recovery path.
 */
@Composable
internal fun ElectricityFulfilment(payment: PurchasePayment) {
    if (payment.quote.service != PurchaseService.Electricity) {
        return
    }
    if (payment.needsReceipt) {
        Text(
            stringResource(R.string.electricity_live_receipt_pending),
            Modifier.testTag("electricity-receipt-pending"),
            color = Warning,
        )
    }
    val receipt = payment.electricityReceipt ?: return
    SelectionContainer {
        Column(Modifier.fillMaxWidth().testTag("electricity-receipt")) {
            InfoRow(stringResource(R.string.electricity_live_receipt_reference), receipt.reference)
            receipt.tokens.forEachIndexed { index, token ->
                Text(token.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    token.value,
                    Modifier.testTag("electricity-token-$index"),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            receipt.unitsKilowattHours?.let {
                InfoRow(stringResource(R.string.electricity_live_units), it)
            }
        }
    }
}
