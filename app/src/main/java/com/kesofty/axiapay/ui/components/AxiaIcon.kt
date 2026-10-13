package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.ui.theme.Ink

enum class Glyph {
    Home, Grid, Activity, Calendar, Phone, Wifi, Bolt, Tv, School, Shield,
    Convert, More, Plus, ArrowUp, ArrowDown, ArrowRight, Chevron, Bell, Eye,
    EyeOff, Close, Check, Wallet, Bank, Person, Clock, Batch, Offline, Search, Help, Brand
}

/** A consistent vector icon set that scales without bitmap assets. */
@Composable
fun AxiaIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = Ink, description: String? = null) {
    Canvas(modifier.size(24.dp).semantics { if (description != null) contentDescription = description }) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(vararg points: Float) {
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                }
                drawPath(path, color, style = stroke)
            }
            fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
                drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r), style = stroke)
            fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r, Offset(x, y), style = stroke)
            when (glyph) {
                Glyph.Home -> { line(3f, 10f, 12f, 3f, 21f, 10f); line(5f, 9f, 5f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 19f, 21f, 19f, 9f) }
                Glyph.Grid -> { box(3f, 3f, 7f, 7f); box(14f, 3f, 7f, 7f); box(3f, 14f, 7f, 7f); box(14f, 14f, 7f, 7f) }
                Glyph.Activity -> { box(5f, 3f, 14f, 18f); line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 12f, 16f) }
                Glyph.Calendar -> { box(3f, 5f, 18f, 16f); line(3f, 10f, 21f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f); line(8f, 15f, 11f, 18f, 16f, 13f) }
                Glyph.Phone -> { box(6f, 2f, 12f, 20f, 3f); line(10f, 5f, 14f, 5f); line(11f, 19f, 13f, 19f) }
                Glyph.Wifi -> { drawArc(color, 218f, 104f, false, Offset(1f, 4f), Size(22f, 22f), style = stroke); drawArc(color, 218f, 104f, false, Offset(5f, 9f), Size(14f, 14f), style = stroke); drawArc(color, 218f, 104f, false, Offset(9f, 14f), Size(6f, 6f), style = stroke); drawCircle(color, 1.2f, Offset(12f, 20f)) }
                Glyph.Bolt -> line(13f, 2f, 4f, 14f, 11f, 14f, 10f, 22f, 20f, 9f, 13f, 9f, 13f, 2f)
                Glyph.Tv -> { box(3f, 6f, 18f, 13f); line(8f, 2f, 12f, 6f, 16f, 2f); line(8f, 22f, 16f, 22f) }
                Glyph.School -> { line(2f, 9f, 12f, 4f, 22f, 9f, 12f, 14f, 2f, 9f); line(6f, 12f, 6f, 17f, 12f, 20f, 18f, 17f, 18f, 12f); line(22f, 9f, 22f, 17f) }
                Glyph.Shield -> { line(12f, 2f, 21f, 6f, 19f, 16f, 12f, 22f, 5f, 16f, 3f, 6f, 12f, 2f); line(8f, 12f, 11f, 15f, 16f, 9f) }
                Glyph.Convert -> { line(4f, 8f, 20f, 8f, 16f, 4f); line(20f, 16f, 4f, 16f, 8f, 20f) }
                Glyph.More -> { circle(5f, 12f, 1f); circle(12f, 12f, 1f); circle(19f, 12f, 1f) }
                Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                Glyph.ArrowUp -> { line(5f, 19f, 19f, 5f); line(7f, 5f, 19f, 5f, 19f, 17f) }
                Glyph.ArrowDown -> { line(19f, 5f, 5f, 19f); line(5f, 7f, 5f, 19f, 17f, 19f) }
                Glyph.ArrowRight -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f, 14f, 18f) }
                Glyph.Chevron -> line(9f, 5f, 16f, 12f, 9f, 19f)
                Glyph.Bell -> { line(5f, 10f, 5f, 15f, 3f, 18f, 21f, 18f, 19f, 15f, 19f, 10f); drawArc(color, 180f, 180f, false, Offset(5f, 3f), Size(14f, 14f), style = stroke); drawArc(color, 0f, 180f, false, Offset(9f, 18f), Size(6f, 4f), style = stroke) }
                Glyph.Eye, Glyph.EyeOff -> { line(2f, 12f, 6f, 7f, 12f, 5f, 18f, 7f, 22f, 12f, 18f, 17f, 12f, 19f, 6f, 17f, 2f, 12f); circle(12f, 12f, 3f); if (glyph == Glyph.EyeOff) line(3f, 3f, 21f, 21f) }
                Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                Glyph.Check -> line(5f, 12f, 10f, 17f, 20f, 6f)
                Glyph.Wallet -> { box(3f, 5f, 18f, 16f); box(14f, 10f, 8f, 6f); line(4f, 5f, 17f, 2f, 17f, 5f); drawCircle(color, 0.8f, Offset(17f, 13f)) }
                Glyph.Bank -> { line(2f, 8f, 12f, 2f, 22f, 8f, 2f, 8f); line(3f, 21f, 21f, 21f); line(5f, 11f, 5f, 18f); line(12f, 11f, 12f, 18f); line(19f, 11f, 19f, 18f) }
                Glyph.Person -> { circle(12f, 7f, 4f); drawArc(color, 180f, 180f, false, Offset(4f, 14f), Size(16f, 14f), style = stroke) }
                Glyph.Clock -> { circle(12f, 12f, 9f); line(12f, 6f, 12f, 12f, 16f, 14f) }
                Glyph.Batch -> { box(7f, 3f, 14f, 14f); line(3f, 7f, 3f, 21f, 17f, 21f); line(11f, 10f, 17f, 10f); line(14f, 7f, 14f, 13f) }
                Glyph.Offline -> { circle(12f, 12f, 9f); line(5f, 19f, 19f, 5f) }
                Glyph.Search -> { circle(10f, 10f, 7f); line(15f, 15f, 21f, 21f) }
                Glyph.Help -> { circle(12f, 12f, 9f); line(9f, 8f, 10f, 6f, 14f, 6f, 15f, 8f, 12f, 12f, 12f, 14f); drawCircle(color, 1f, Offset(12f, 18f)) }
                Glyph.Brand -> { val path = Path().apply { moveTo(12f, 1f); lineTo(15.8f, 8.2f); lineTo(23f, 12f); lineTo(15.8f, 15.8f); lineTo(12f, 23f); lineTo(8.2f, 15.8f); lineTo(1f, 12f); lineTo(8.2f, 8.2f); close() }; drawPath(path, color) }
            }
        }
    }
}
