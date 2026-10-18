package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kesofty.axiapay.domain.Payment
import com.kesofty.axiapay.domain.PaymentStatus
import com.kesofty.axiapay.ui.icon
import com.kesofty.axiapay.ui.label
import com.kesofty.axiapay.ui.money
import com.kesofty.axiapay.ui.theme.*
import kotlin.math.abs

@Composable
fun glyphTint(icon: Glyph): Color =
    when (icon) {
        Glyph.Wifi,
        Glyph.Convert -> Lavender
        Glyph.Bolt,
        Glyph.School -> Peach
        Glyph.Tv,
        Glyph.Bank -> Sky
        else -> Mint.copy(alpha = 0.6f)
    }

@Composable
fun IconTile(icon: Glyph, modifier: Modifier = Modifier, tint: Color = glyphTint(icon)) {
    Box(
        modifier.size(46.dp).background(tint, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AxiaIcon(icon, Modifier.size(22.dp))
    }
}

/**
 * Outlines compact icon actions while retaining their accessible label and standard touch target.
 */
@Composable
fun RoundIconButton(
    icon: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick,
        modifier
            .size(48.dp)
            .background(Color.White, CircleShape)
            .border(controlBorder(), CircleShape),
    ) {
        AxiaIcon(icon, description = label, modifier = Modifier.size(21.dp))
    }
}

/** Shares section typography and keeps actions readable when titles need more space. */
@Composable
fun SectionHeading(
    title: String,
    action: String? = null,
    badge: String? = null,
    onClick: () -> Unit = {},
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackAction = maxWidth.value / fontScale < 280f
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (!stackAction && action != null) SectionAction(action, onClick)
            }
            if (stackAction && action != null) SectionAction(action, onClick)
        }
    }
}

/** Retains the established section link's label, chevron and accessible touch target. */
@Composable
private fun SectionAction(label: String, onClick: () -> Unit) {
    TextButton(onClick, contentPadding = PaddingValues(start = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(4.dp))
        AxiaIcon(Glyph.Chevron, Modifier.size(13.dp), Pine)
    }
}

@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick,
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun StatusPill(status: PaymentStatus) {
    val tint =
        when (status) {
            PaymentStatus.Successful -> Pine
            PaymentStatus.Waiting,
            PaymentStatus.Processing -> Warning
            PaymentStatus.Scheduled -> Color(0xFF655092).modeColor()
            PaymentStatus.Failed -> Danger
        }
    Text(
        status.label,
        color = tint,
        style = MaterialTheme.typography.labelSmall,
        modifier =
            Modifier.background(tint.copy(alpha = .09f), RoundedCornerShape(20.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** Keeps the demo activity row while moving details below the title when text needs more room. */
@Composable
fun PaymentRow(payment: Payment, hidden: Boolean = false, onClick: () -> Unit) {
    val sign = if (payment.amount > 0) "+" else "−"
    val amountLabel = if (hidden) "••••" else sign + money(abs(payment.amount))
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackDetails = maxWidth.value / fontScale < 300f
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(payment.icon)
            Spacer(Modifier.width(12.dp))
            FlowRow(
                Modifier.weight(1f),
                maxItemsInEachRow = if (stackDetails) 1 else 2,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        payment.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = if (stackDetails) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(payment.time, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = if (stackDetails) Alignment.Start else Alignment.End) {
                    Text(
                        amountLabel,
                        color = if (payment.amount > 0) Pine else Ink,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    StatusPill(payment.status)
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1.3f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
fun EmptyState(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(Glyph.Activity, tint = Mint)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Keep exact wallet amounts legible as their digits or the user's font scale increase. */
@Composable
fun MoneyAmount(text: String, modifier: Modifier = Modifier, color: Color = Ink) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = 1,
        style = MaterialTheme.typography.displaySmall.copy(color = color),
        autoSize =
            TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 38.sp, stepSize = 1.sp),
    )
}
