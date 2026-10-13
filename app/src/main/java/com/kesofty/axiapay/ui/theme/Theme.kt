package com.kesofty.axiapay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun AxiapayTheme(offline: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOfflinePalette provides offline) {
        val colors =
            lightColorScheme(
                primary = Pine,
                onPrimary = Color.White,
                primaryContainer = Mint,
                onPrimaryContainer = DeepPine,
                secondary = Pine,
                secondaryContainer = Mint,
                background = Canvas,
                onBackground = Ink,
                surface = Color.White,
                onSurface = Ink,
                surfaceVariant = Canvas,
                onSurfaceVariant = Muted,
                outline = Muted,
                outlineVariant = Line,
                error = Danger,
            )
        val resolved =
            when (offline) {
                false -> colors
                true ->
                    colors.copy(
                        onSecondary = Color.White,
                        onSecondaryContainer = Ink,
                        tertiary = Muted,
                        onTertiary = Color.White,
                        tertiaryContainer = Line,
                        onTertiaryContainer = Ink,
                        inversePrimary = Mint,
                        inverseSurface = DeepPine,
                        inverseOnSurface = Color.White,
                        onError = Color.White,
                        errorContainer = Line,
                        onErrorContainer = Ink,
                        surfaceTint = Pine,
                        surfaceBright = Color.White,
                        surfaceDim = Line,
                        surfaceContainer = Canvas,
                        surfaceContainerHigh = Line,
                        surfaceContainerHighest = Line,
                        surfaceContainerLow = Canvas,
                        surfaceContainerLowest = Color.White,
                    )
            }
        MaterialTheme(colorScheme = resolved, typography = Typography, content = content)
    }
}
