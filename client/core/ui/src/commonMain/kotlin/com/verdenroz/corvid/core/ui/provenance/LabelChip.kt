package com.verdenroz.corvid.core.ui.provenance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.label_guess
import org.jetbrains.compose.resources.stringResource

private val ChipShape = RoundedCornerShape(6.dp)

/** Who set a label shows in its outline: filled is a person's or the labeler's, dashed with "?" is a model guess, a funnel is a filter. */
@Composable
fun LabelChip(
    label: Label,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    val name = label.name.replaceFirstChar { it.uppercase() }
    val shaped =
        when (label) {
            is Label.Yours -> modifier.background(colors.accentSoft, ChipShape)
            is Label.Labeler -> modifier.background(colors.chip, ChipShape)
            is Label.Guess -> modifier.dashedOutline(colors.inkFaint)
            is Label.Rule -> modifier.border(1.dp, colors.line, ChipShape)
        }
    val ink = if (label is Label.Yours || label is Label.Labeler) colors.onAccentSoft else colors.inkMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = shaped.height(20.dp).padding(horizontal = 7.dp),
    ) {
        if (label is Label.Rule) Icon(CorvidIcons.Filter, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        Text(
            if (label is Label.Guess) stringResource(Res.string.label_guess, name) else name,
            style = CorvidTheme.type.chip,
            color = ink,
            maxLines = 1,
        )
    }
}

private fun Modifier.dashedOutline(color: Color): Modifier =
    drawBehind {
        val stroke = 1.dp.toPx()
        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
        )
    }
