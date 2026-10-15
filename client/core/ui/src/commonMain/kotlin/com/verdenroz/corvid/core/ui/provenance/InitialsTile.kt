package com.verdenroz.corvid.core.ui.provenance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** A small rounded square holding the initials of [name], for a source in a list or a card. */
@Composable
fun InitialsTile(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).background(colors.chip, CorvidShape.small),
    ) {
        Text(initials(name), style = CorvidTheme.type.chip, color = colors.ink, maxLines = 1)
    }
}
