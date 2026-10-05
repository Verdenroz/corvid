package com.verdenroz.corvid.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corners: [pill] for buttons and counts, [small] for chips and keys, [medium] for fields, frames and panels. */
object CorvidShape {
    val pill = RoundedCornerShape(50)
    val small = RoundedCornerShape(6.dp)
    val medium = RoundedCornerShape(12.dp)
}

/** One horizontal gutter for every screen edge, wide and narrow; the reader and thread use the wider [readingGutter]. */
object CorvidSpacing {
    fun gutter(wide: Boolean): Dp = if (wide) 24.dp else 16.dp

    fun readingGutter(wide: Boolean): Dp = if (wide) 32.dp else 18.dp
}

internal val MaterialShapes =
    Shapes(
        extraSmall = CorvidShape.small,
        small = CorvidShape.small,
        medium = CorvidShape.medium,
        large = CorvidShape.medium,
        extraLarge = CorvidShape.medium,
    )
