package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Names [content] in a plain tooltip on hover or long press, below it or [beside] it; [enabled] false leaves it unlabelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelTooltip(
    label: String,
    modifier: Modifier = Modifier,
    beside: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val anchor = if (beside) TooltipAnchorPosition.End else TooltipAnchorPosition.Below
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(anchor),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
        enableUserInput = enabled,
        content = content,
    )
}
