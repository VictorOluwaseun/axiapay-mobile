package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.Canvas as DrawingCanvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.ui.theme.*

/**
 * Renders caller-owned radio state with a 48dp target and a decorative corner check. Selection
 * changes paint only: border width, padding and label measurement stay identical in both states.
 * The owner supplies a selectable group and receives the same explicit selection callback.
 */
@Composable
fun SelectionCard(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    selectedContainerColor: Color = Mint,
    unselectedContainerColor: Color = Color.White,
    content: @Composable BoxScope.() -> Unit,
) {
    val container =
        when {
            !enabled -> Canvas
            selected -> selectedContainerColor
            else -> unselectedContainerColor
        }
    val foreground =
        when {
            !enabled -> Muted
            selected -> Pine
            else -> Ink
        }
    Surface(
        modifier =
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .selectable(
                    selected,
                    enabled = enabled,
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                    onClick = onSelect,
                ),
        shape = shape,
        color = container,
        border = controlBorder(selected, enabled),
    ) {
        Box(propagateMinConstraints = true) {
            Box(Modifier.padding(contentPadding), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides foreground) { content() }
            }
            // matchParentSize excludes the mark from intrinsic and normal content measurement.
            if (selected) SelectionCorner(Modifier.matchParentSize(), foreground)
        }
    }
}

/** Centers one label in a radio card without inserting or removing an inline selection icon. */
@Composable
fun SelectionChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    SelectionCard(selected, onSelect, modifier, enabled) {
        Text(label, style = textStyle, textAlign = TextAlign.Center)
    }
}

/** Paints the logical bottom-end corner and tick without adding semantics or a separate target. */
@Composable
private fun SelectionCorner(modifier: Modifier, color: Color) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    DrawingCanvas(modifier) {
        val side = 24.dp.toPx().coerceAtMost(size.minDimension)
        val left = if (isRtl) 0f else size.width - side
        val top = size.height - side
        val outer = if (isRtl) left else left + side
        val inner = if (isRtl) left + side else left
        val triangle =
            Path().apply {
                moveTo(outer, top)
                lineTo(outer, size.height)
                lineTo(inner, size.height)
                close()
            }
        drawPath(triangle, color)
        val shift = if (isRtl) 0.35f else 0f
        val tick =
            Path().apply {
                moveTo(left + side * (0.46f - shift), top + side * 0.66f)
                lineTo(left + side * (0.60f - shift), top + side * 0.80f)
                lineTo(left + side * (0.83f - shift), top + side * 0.50f)
            }
        drawPath(
            tick,
            Color.White,
            style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
