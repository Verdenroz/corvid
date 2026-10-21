package com.verdenroz.corvid.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** Where a step of a sign-in or approval stands, read out as it changes; a failure is in danger. */
@Composable
fun StatusLine(
    text: String,
    failed: Boolean,
    style: TextStyle = CorvidTheme.type.body,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    Text(
        text,
        style = style,
        color = if (failed) colors.danger else colors.ink,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
