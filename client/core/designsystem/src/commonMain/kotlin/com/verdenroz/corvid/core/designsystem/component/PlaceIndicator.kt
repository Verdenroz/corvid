package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The mark under the chosen item of a sidebar or a list of sections: a short accent rule centred on [centerX]. */
fun DrawScope.drawIndicator(
    color: Color,
    centerX: Float = size.width / 2,
    width: Dp = 20.dp,
) {
    val widthPx = width.toPx()
    drawRoundRect(
        color,
        Offset(centerX - widthPx / 2, size.height - 2.dp.toPx()),
        Size(widthPx, 2.dp.toPx()),
        CornerRadius(1.dp.toPx()),
    )
}
