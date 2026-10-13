package com.kesofty.axiapay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kesofty.axiapay.R

@OptIn(ExperimentalTextApi::class)
val Manrope = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.manrope, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

private fun style(size: Int, weight: FontWeight = FontWeight.Medium, tracking: Float = 0f, height: Int = size + 7) = TextStyle(
    fontFamily = Manrope, fontWeight = weight, fontSize = size.sp, letterSpacing = tracking.sp, lineHeight = height.sp,
)
val Typography = Typography(
    displaySmall = style(38, FontWeight.Bold, -1.5f, 44),
    headlineLarge = style(30, FontWeight.ExtraBold, -1f),
    headlineMedium = style(25, FontWeight.Bold, -0.8f),
    titleLarge = style(20, FontWeight.Bold, -0.5f),
    titleMedium = style(16, FontWeight.Bold, -0.3f),
    titleSmall = style(15, FontWeight.Bold, height = 22),
    bodyLarge = style(16, FontWeight.Normal, height = 24),
    bodyMedium = style(15, height = 23),
    bodySmall = style(13, height = 20),
    labelLarge = style(16, FontWeight.Bold, height = 24),
    labelMedium = style(14, FontWeight.SemiBold, height = 20),
    labelSmall = style(12, FontWeight.SemiBold, height = 18),
)
