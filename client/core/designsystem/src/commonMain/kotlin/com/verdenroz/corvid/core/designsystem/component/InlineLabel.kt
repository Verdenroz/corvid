package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/**
 * A `BasicTextField` decoration for a field set in a hairline row: [label] in a fixed column, then
 * the text. Drawn inside the field, the label names it to a screen reader and a tap on it focuses it.
 */
fun inlineLabel(
    label: String,
    width: Dp = 64.dp,
): @Composable (@Composable () -> Unit) -> Unit =
    { field ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 42.dp)) {
            Text(label, style = CorvidTheme.type.name, color = CorvidTheme.colors.inkMuted, modifier = Modifier.width(width))
            Box(Modifier.weight(1f)) { field() }
        }
    }
