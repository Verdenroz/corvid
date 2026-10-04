package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.verdenroz.corvid.core.designsystem.CorvidColors
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.resources.Res
import com.verdenroz.corvid.core.designsystem.resources.corvid_mark
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

/** The bird's bounds inside the launcher vector's 108-unit viewport, which keeps the adaptive-icon safe zone. */
private const val BIRD_LEFT = 28.77f / 108f
private const val BIRD_TOP = 32.10f / 108f
private const val BIRD_WIDTH = 50.46f / 108f
private const val BIRD_HEIGHT = 43.80f / 108f

/** Violet holds until 70% along the diagonal so the mark still reads violet; green shows only at the tip. */
private fun markSheen(colors: CorvidColors) = arrayOf(0f to colors.sheenStart, 0.7f to colors.accent, 1f to colors.sheenEnd)

/** The app icon's crow in the feather's sheen. [width] is the bird's own width; layout sees the bird, not the icon's margin. */
@Composable
fun CorvidMark(
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    Image(
        painter = painterResource(Res.drawable.corvid_mark),
        contentDescription = null,
        modifier =
            modifier
                .layout { measurable, _ ->
                    val full = (width.toPx() / BIRD_WIDTH).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(full, full))
                    layout((full * BIRD_WIDTH).roundToInt(), (full * BIRD_HEIGHT).roundToInt()) {
                        placeable.place(-(full * BIRD_LEFT).roundToInt(), -(full * BIRD_TOP).roundToInt())
                    }
                }.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithCache {
                    val sheen = Brush.linearGradient(*markSheen(colors), start = Offset.Zero, end = Offset(size.width, size.height))
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = sheen, blendMode = BlendMode.SrcIn)
                    }
                },
    )
}
