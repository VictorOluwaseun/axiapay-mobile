package com.kesofty.axiapay.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = ControlBorderWidth) {
            ElectricityMeterType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = selected == type,
                    onClick = { onSelect(type) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index,
                            ElectricityMeterType.entries.size,
                            RoundedCornerShape(12.dp),
                        ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("meter-type-${type.name}"),
                    border =
                        BorderStroke(
                            ControlBorderWidth,
                            if (selected == type) Pine else ControlOutline,
                        ),
                    colors =
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = Mint,
                            activeContentColor = Pine,
                            activeBorderColor = Pine,
                            inactiveContainerColor = Color.White,
                            inactiveContentColor = Ink,
                            inactiveBorderColor = ControlOutline,
                        ),
                ) {
                    Text(
                        stringResource(electricityMeterTypeLabel(type)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
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
