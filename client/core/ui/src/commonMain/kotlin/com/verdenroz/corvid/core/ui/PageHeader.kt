package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons

@Composable
fun PageHeader(
    title: String?,
    backLabel: String,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 16.dp)) {
        IconButton(onClick = onBack) {
            Icon(CorvidIcons.Back, backLabel, tint = CorvidTheme.colors.inkMuted)
        }
        title?.let { Text(it, style = CorvidTheme.type.headline, modifier = Modifier.semantics { heading() }) }
    }
}
