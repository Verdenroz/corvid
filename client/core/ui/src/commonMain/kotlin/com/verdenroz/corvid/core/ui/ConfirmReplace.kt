package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.replace_body
import com.verdenroz.corvid.core.ui.resources.replace_cancel
import com.verdenroz.corvid.core.ui.resources.replace_confirm
import com.verdenroz.corvid.core.ui.resources.replace_declined
import com.verdenroz.corvid.core.ui.resources.replace_title
import org.jetbrains.compose.resources.stringResource

/** Asks before Corvid's filters replace the Sieve script [scriptName] that another client has active. */
@Composable
fun ConfirmReplace(
    scriptName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    Column(
        modifier.fillMaxWidth().background(colors.dangerSoft).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.replace_title), style = CorvidTheme.type.metaStrong, color = colors.onDangerSoft)
        Text(stringResource(Res.string.replace_body, scriptName), style = CorvidTheme.type.meta, color = colors.onDangerSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(Res.string.replace_confirm), onClick = onConfirm)
            PillButton(stringResource(Res.string.replace_cancel), null, onDismiss)
        }
    }
}

/** What stays on screen once the replacement is refused. */
fun replaceDeclined(): UiText = uiText(Res.string.replace_declined)
