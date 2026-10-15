package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.presentation.ProviderPickerState
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import com.kesofty.axiapay.ui.theme.Canvas

/** Keeps all bundled provider artwork reviewable at compact width in both connection palettes. */
@Preview(name = "Provider logos", widthDp = 360, heightDp = 300, showBackground = true)
@Composable
private fun ProviderLogosPreview() {
    ProviderGallery(offline = false)
}

/** Exposes greyscale artwork and selection without runtime connectivity or payment dependencies. */
@Preview(
    name = "Offline provider logos",
    widthDp = 360,
    heightDp = 300,
    showBackground = true,
    fontScale = 1.3f,
)
@Composable
private fun OfflineProviderLogosPreview() {
    ProviderGallery(offline = true)
}

/** Supplies synthetic presentation choices to the same selector used by payment forms. */
@Composable
private fun ProviderGallery(offline: Boolean) {
    AxiapayTheme(offline = offline) {
        Surface(color = Canvas) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProviderChoices(listOf("MTN", "Airtel", "Glo"), "MTN", onSelect = {})
                ProviderChoices(listOf("DStv", "GOtv", "StarTimes"), "GOtv", onSelect = {})
                ElectricityProviderSelector(
                    electricityPreviewProviders,
                    "Ikeja Electric",
                    ProviderPickerState(),
                    onPickerIntent = {},
                    onSelect = {},
                )
            }
        }
    }
}

/** Supplies presentation fixtures without constructing a ViewModel or invoking payment rules. */
private val electricityPreviewProviders =
    listOf(
        "Ikeja Electric",
        "Eko Electric",
        "Abuja Electric",
        "Kano Electric",
        "Port Harcourt Electric",
        "Jos Electric",
        "Kaduna Electric",
        "Enugu Electric",
        "Ibadan Electric",
        "Benin Electric",
        "Aba Electric",
        "Yola Electric",
    )

/** Makes readable names, small logos and selected rows reviewable outside the modal host. */
@Preview(name = "Electricity provider list", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun ElectricityProviderListPreview() {
    AxiapayTheme {
        Surface {
            ElectricityProviderPickerContent(
                electricityPreviewProviders,
                "Eko Electric",
                "",
                {},
                {},
                {},
            )
        }
    }
}

/** Checks the same list layout with enlarged text and the automatic offline palette. */
@Preview(
    name = "Offline electricity provider list",
    widthDp = 360,
    heightDp = 720,
    fontScale = 1.3f,
)
@Composable
private fun OfflineElectricityProviderListPreview() {
    AxiapayTheme(offline = true) {
        Surface {
            ElectricityProviderPickerContent(
                electricityPreviewProviders,
                "Eko Electric",
                "",
                {},
                {},
                {},
            )
        }
    }
}
