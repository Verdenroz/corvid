package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.discard
import com.verdenroz.corvid.core.ui.resources.keep_editing
import org.jetbrains.compose.resources.stringResource

@Composable
fun DiscardQuestion(
    question: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmLabel: String = stringResource(Res.string.discard),
    cancelLabel: String = stringResource(Res.string.keep_editing),
) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(colors.dangerSoft).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(question, style = CorvidTheme.type.metaStrong, color = colors.onDangerSoft, modifier = Modifier.weight(1f))
        PillButton(confirmLabel, null, onConfirm, height = 30.dp)
        PillButton(cancelLabel, null, onCancel, height = 30.dp)
    }
}
