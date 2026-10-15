package com.kesofty.axiapay.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.ui.theme.*

/**
 * Renders compact service-provider logos for individual and bulk forms. The caller owns the
 * available providers and selected value; choosing a logo emits its unchanged provider identity
 * only. The optional international entry stays disabled and emits no selection.
 */
@Composable
fun ProviderChoices(
    options: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    showInternationalAirtime: Boolean = false,
    onSelect: (String) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            options.forEach { provider ->
                key(provider) {
                    ProviderChoice(
                        provider,
                        selected == provider,
                        { onSelect(provider) },
                        // A bounded chip keeps artwork compact instead of expanding across the
                        // form.
                        Modifier.width(88.dp),
                    )
                }
            }
        }
        if (showInternationalAirtime) InternationalAirtimePlaceholder()
    }
}

/** Shows the future country/network flow without emitting an intent or selecting a provider. */
@Composable
private fun InternationalAirtimePlaceholder() {
    OutlinedButton(
        onClick = {},
        enabled = false,
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("provider-international-airtime"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(ControlBorderWidth, ControlOutline),
        colors =
            ButtonDefaults.outlinedButtonColors(
                disabledContainerColor = Canvas,
                disabledContentColor = Muted,
            ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        AxiaIcon(Glyph.Globe, Modifier.size(24.dp), Muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.provider_international_airtime),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.provider_coming_soon),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Makes the entire outlined logo tile selectable and exposes its name and selection to TalkBack.
 */
@Composable
private fun ProviderChoice(
    provider: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier =
            modifier
                .testTag("provider-$provider")
                .clip(RoundedCornerShape(12.dp))
                .selectable(selected, role = Role.RadioButton, onClick = onSelect)
                .semantics(mergeDescendants = true) { contentDescription = provider },
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border =
            BorderStroke(
                if (selected) 2.dp else ControlBorderWidth,
                if (selected) Pine else ControlOutline,
            ),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
            ProviderArtwork(
                provider,
                Modifier.padding(horizontal = 12.dp, vertical = 12.dp).width(60.dp),
            )
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(12.dp)
                        .background(Pine, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AxiaIcon(Glyph.Check, Modifier.size(8.dp), Color.White)
                }
            }
        }
    }
}

/**
 * Preserves brand aspect ratios and applies the shared offline palette to local image pixels.
 * Unmapped providers retain a readable name; selection never derives provider identity from
 * artwork.
 */
@Composable
internal fun ProviderArtwork(provider: String, modifier: Modifier) {
    val logo = providerLogoResource(provider)
    if (logo == null) {
        Text(
            provider,
            modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
            textAlign = TextAlign.Center,
        )
        return
    }
    // A dark plaque preserves contrast for artwork supplied for dark backgrounds.
    val backing =
        when (provider) {
            "StarTimes",
            "T2 (9mobile)",
            "Port Harcourt Electric" -> Color(0xFF153C63).modeColor()
            else -> Color.Transparent
        }
    val offline = LocalOfflinePalette.current
    val colorFilter =
        remember(offline) {
            if (offline) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            else null
        }
    Image(
        painterResource(logo),
        contentDescription = null,
        modifier = modifier.background(backing, RoundedCornerShape(6.dp)).height(26.dp),
        contentScale = ContentScale.Fit,
        colorFilter = colorFilter,
    )
}

/** Maps existing provider identities to presentation assets; sources live in artwork/providers. */
@DrawableRes
private fun providerLogoResource(provider: String): Int? =
    when (provider) {
        "MTN" -> R.drawable.provider_mtn
        "Airtel" -> R.drawable.provider_airtel
        "Glo" -> R.drawable.provider_glo
        "T2 (9mobile)" -> R.drawable.provider_t2
        "Smile" -> R.drawable.provider_smile
        "DStv" -> R.drawable.provider_dstv
        "GOtv" -> R.drawable.provider_gotv
        "StarTimes" -> R.drawable.provider_startimes
        "Ikeja Electric" -> R.drawable.provider_ikeja
        "Eko Electric" -> R.drawable.provider_eko
        "Abuja Electric" -> R.drawable.provider_abuja
        "Kano Electric" -> R.drawable.provider_kano
        "Port Harcourt Electric" -> R.drawable.provider_port_harcourt
        "Jos Electric" -> R.drawable.provider_jos
        "Kaduna Electric" -> R.drawable.provider_kaduna
        "Enugu Electric" -> R.drawable.provider_enugu
        "Ibadan Electric" -> R.drawable.provider_ibadan
        "Benin Electric" -> R.drawable.provider_benin
        "Aba Electric" -> R.drawable.provider_aba
        "Yola Electric" -> R.drawable.provider_yola
        else -> null
    }
