package com.verdenroz.corvid.core.ui.provenance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** How much of the idle window is left before the key drops, drawn clockwise from the top in the feather's sheen. */
@Composable
fun KeyRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val colors = CorvidTheme.colors
    Canvas(modifier.size(size)) {
        val width = this.size.width * 2.5f / 22f
        val inset = width / 2 + this.size.width * 0.5f / 22f
        val arc = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(colors.line, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(width))
        drawArc(
            brush =
                Brush.linearGradient(
                    listOf(colors.sheenStart, colors.sheenEnd),
                    Offset.Zero,
                    Offset(this.size.width, this.size.height),
                ),
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = arc,
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}
