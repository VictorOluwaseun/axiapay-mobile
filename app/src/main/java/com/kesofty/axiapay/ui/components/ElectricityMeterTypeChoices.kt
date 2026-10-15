package com.kesofty.axiapay.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.ui.theme.*

/**
 * Renders one caller-owned meter type and emits typed choices through the form's existing intent.
 */
@Composable
fun ElectricityMeterTypeChoices(
    selected: ElectricityMeterType,
    onSelect: (ElectricityMeterType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.electricity_meter_type),
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
        )
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ElectricityMeterType.entries.forEach { type ->
                SelectionChip(
                    label = stringResource(electricityMeterTypeLabel(type)),
                    selected = selected == type,
                    onSelect = { onSelect(type) },
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().testTag("meter-type-${type.name}"),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** Maps stable domain values to localized display copy without converting labels back into IDs. */
@StringRes
internal fun electricityMeterTypeLabel(type: ElectricityMeterType): Int =
    when (type) {
        ElectricityMeterType.Prepaid -> R.string.electricity_meter_prepaid
        ElectricityMeterType.Postpaid -> R.string.electricity_meter_postpaid
    }

/** Keeps the compact two-choice control reviewable without creating payment state. */
@Preview(widthDp = 360, heightDp = 130, showBackground = true)
@Composable
private fun MeterTypeChoicesPreview() {
    AxiapayTheme {
        Surface {
            ElectricityMeterTypeChoices(ElectricityMeterType.Prepaid, {}, Modifier.padding(24.dp))
        }
    }
}

/** Checks selected borders, text scaling and the greyscale palette together. */
@Preview(widthDp = 360, heightDp = 160, fontScale = 1.3f, showBackground = true)
@Composable
private fun OfflineMeterTypeChoicesPreview() {
    AxiapayTheme(offline = true) {
        Surface {
            ElectricityMeterTypeChoices(ElectricityMeterType.Postpaid, {}, Modifier.padding(24.dp))
        }
    }
}
