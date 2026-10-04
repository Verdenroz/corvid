package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.Correction
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.teach_apply
import com.verdenroz.corvid.feature.mailbox.resources.teach_change
import com.verdenroz.corvid.feature.mailbox.resources.teach_change_hint
import com.verdenroz.corvid.feature.mailbox.resources.teach_right
import com.verdenroz.corvid.feature.mailbox.resources.teach_wrong
import org.jetbrains.compose.resources.stringResource

/** Right, Wrong or Change for a model guess; Change asks for the label inline rather than in a dialog. */
@Composable
internal fun TeachControls(
    guess: Label.Guess,
    answering: Boolean,
    error: UiText?,
    onAnswer: (Correction) -> Unit,
) {
    var changing by rememberSaveable(guess) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val enabled = !answering
            PillButton(
                stringResource(Res.string.teach_right),
                CorvidIcons.Check,
                { if (enabled) onAnswer(Correction.Right(guess)) },
                height = 28.dp,
            )
            PillButton(
                stringResource(Res.string.teach_wrong),
                CorvidIcons.Close,
                { if (enabled) onAnswer(Correction.Wrong(guess)) },
                height = 28.dp,
            )
            PillButton(stringResource(Res.string.teach_change), CorvidIcons.Tag, { changing = !changing }, height = 28.dp)
        }
        if (changing) ChangeField(enabled = !answering) { keyword -> onAnswer(Correction.Change(guess, keyword)) }
        error?.let { Text(it.asString(), style = CorvidTheme.type.meta, color = CorvidTheme.colors.danger) }
    }
}

@Composable
private fun ChangeField(
    enabled: Boolean,
    onApply: (String) -> Unit,
) {
    val colors = CorvidTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    val keyword = CorvidKeywords.userKeyword(text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = CorvidTheme.type.meta.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            modifier =
                Modifier
                    .widthIn(min = 120.dp, max = 200.dp)
                    .height(28.dp)
                    .border(1.dp, colors.line, CorvidShape.small)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            decorationBox = { field ->
                if (text.isEmpty()) {
                    Text(
                        stringResource(Res.string.teach_change_hint),
                        style = CorvidTheme.type.meta,
                        color = colors.inkFaint,
                    )
                }
                field()
            },
        )
        PillButton(stringResource(Res.string.teach_apply), null, { if (enabled && keyword != null) onApply(keyword) }, height = 28.dp)
    }
}
