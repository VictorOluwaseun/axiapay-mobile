package com.kesofty.axiapay.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Keeps chip, selector, panel and icon-action boundaries crisp without changing their layout. */
val ControlBorderWidth = 1.25.dp

/**
 * Resolves visible selected/unselected strokes in either palette, with softer disabled outlines.
 */
@Composable
fun controlBorder(selected: Boolean = false, enabled: Boolean = true): BorderStroke {
    val outline = if (selected) Pine else ControlOutline
    return BorderStroke(ControlBorderWidth, if (enabled) outline else outline.copy(alpha = .38f))
}
