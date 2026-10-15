package com.verdenroz.corvid.core.ui.suggestion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** A suggestion's action: filled with [fill], or bare text when it is null. The colors are the card's, which is not always on paper. */
@Composable
fun SuggestionButton(
    text: String,
    onClick: () -> Unit,
    ink: Color,
    fill: Color? = null,
) {
    val shaped = if (fill == null) Modifier else Modifier.background(fill, CorvidShape.medium)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            shaped
                .height(36.dp)
                .defaultMinSize(minWidth = 36.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = if (fill == null) 10.dp else 14.dp),
    ) {
        Text(text, style = CorvidTheme.type.action, color = ink)
    }
}
