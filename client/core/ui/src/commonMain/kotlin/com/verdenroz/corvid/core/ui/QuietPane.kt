package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** One muted line centred in an otherwise empty screen: an empty list, a failure, a placeholder. */
@Composable
fun QuietPane(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted, modifier = Modifier.widthIn(max = 420.dp))
    }
}
