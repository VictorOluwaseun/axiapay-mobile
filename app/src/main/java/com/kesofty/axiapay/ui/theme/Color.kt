package com.kesofty.axiapay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalOfflinePalette = staticCompositionLocalOf { false }

/** Preserve luminance and contrast without making enabled controls look disabled. */
@Composable
fun Color.modeColor(): Color {
    if (!LocalOfflinePalette.current) return this
    val grey = red * .2126f + green * .7152f + blue * .0722f
    return Color(grey, grey, grey, alpha)
}

val Pine: Color
    @Composable get() = Color(0xFF124C42).modeColor()
val DeepPine: Color
    @Composable get() = Color(0xFF103F38).modeColor()
val Mint: Color
    @Composable get() = Color(0xFFC5F2D8).modeColor()
val Canvas: Color
    @Composable get() = Color(0xFFF6F8F7).modeColor()
val Ink: Color
    @Composable get() = Color(0xFF173D35).modeColor()
val Muted: Color
    @Composable get() = Color(0xFF5B6E66).modeColor()
val Line: Color
    @Composable get() = Color(0xFFE5ECE8).modeColor()
val Peach: Color
    @Composable get() = Color(0xFFFBE8D8).modeColor()
val Lavender: Color
    @Composable get() = Color(0xFFECE8F7).modeColor()
val Sky: Color
    @Composable get() = Color(0xFFE4F0FA).modeColor()
val Warning: Color
    @Composable get() = Color(0xFF94621A).modeColor()
val Danger: Color
    @Composable get() = Color(0xFFB04444).modeColor()
