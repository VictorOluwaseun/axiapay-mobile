package com.kesofty.axiapay.ui.auth

import androidx.compose.foundation.Canvas as DrawingCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*

/** Welcomes people with the existing payment vocabulary and explicit routes into the demo. */
@Composable
internal fun AuthWelcome(onCreate: () -> Unit, onSignIn: () -> Unit, onExplore: () -> Unit) {
    EverydayIllustration()
    Spacer(Modifier.height(28.dp))
    AuthHeading(R.string.auth_welcome_title, R.string.auth_welcome_body)
    Spacer(Modifier.height(30.dp))
    PrimaryButton(stringResource(R.string.auth_get_started), onClick = onCreate)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onSignIn,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Text(
            stringResource(R.string.auth_have_account),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    TextButton(onExplore, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.auth_explore), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * A native, scalable wallet illustration connects the welcome screen to the dashboard's services.
 */
@Composable
private fun EverydayIllustration() {
    val lineColor = Line
    Box(Modifier.fillMaxWidth().height(270.dp).clearAndSetSemantics {}) {
        DrawingCanvas(Modifier.fillMaxSize()) {
            drawCircle(
                lineColor,
                size.minDimension * .45f,
                Offset(size.width * .5f, size.height * .5f),
                style = Stroke(1.dp.toPx()),
            )
            drawCircle(
                lineColor,
                size.minDimension * .33f,
                Offset(size.width * .5f, size.height * .5f),
                style = Stroke(1.dp.toPx()),
            )
        }
        Box(
            Modifier.align(Alignment.Center)
                .size(210.dp)
                .background(Mint.copy(alpha = .35f), CircleShape),
        )
        Column(
            Modifier.align(Alignment.Center)
                .rotate(-7f)
                .width(232.dp)
                .background(DeepPine, RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AxiaIcon(Glyph.Brand, Modifier.size(24.dp), Mint)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.auth_brand),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.auth_everyday),
                        color = Mint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.auth_sorted),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                AxiaIcon(Glyph.Check, Modifier.size(32.dp), Mint)
            }
        }
        ServiceMedallion(
            Glyph.Wifi,
            Lavender,
            Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 22.dp).rotate(8f),
        )
        ServiceMedallion(
            Glyph.Bolt,
            Peach,
            Modifier.align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 10.dp)
                .rotate(-12f),
        )
        ServiceMedallion(
            Glyph.Phone,
            Mint,
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 4.dp).rotate(10f),
        )
    }
}

/** Uses the dashboard's outlined service icons as decorative welcome illustration elements. */
@Composable
private fun ServiceMedallion(icon: Glyph, color: Color, modifier: Modifier) {
    Box(
        modifier
            .size(68.dp)
            .background(color, RoundedCornerShape(23.dp))
            .border(5.dp, Canvas, RoundedCornerShape(23.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AxiaIcon(icon, Modifier.size(28.dp))
    }
}
