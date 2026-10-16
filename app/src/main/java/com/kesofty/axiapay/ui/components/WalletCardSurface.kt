package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.ui.theme.DeepPine
import com.kesofty.axiapay.ui.theme.Mint

/**
 * Preserve the established wallet card and offline palette while callers supply demo or server
 * content.
 */
@Composable
internal fun WalletCardSurface(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(DeepPine)) {
        val ringColor = Mint
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * 1.02f, size.height * .24f)
            repeat(4) { index ->
                drawCircle(
                    ringColor.copy(alpha = .06f + index * .013f),
                    size.width * (.19f + index * .1f),
                    center,
                    style = Stroke(17.dp.toPx()),
                )
            }
        }
        Column(Modifier.padding(22.dp), content = content)
    }
}
