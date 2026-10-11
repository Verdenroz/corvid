package com.verdenroz.corvid.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.Contact
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_show_copies
import org.jetbrains.compose.resources.stringResource

private val LABEL_WIDTH = 64.dp

/** What a recipient field reports back: keystrokes with the caret, and moves within its suggestion list. */
internal class RecipientActions(
    val onEdit: (RecipientField, String, Int) -> Unit,
    val onMove: (Int) -> Unit,
    val onAccept: (Int?) -> Unit,
    val onDismiss: () -> Boolean,
)

/**
 * A To, Cc or Bcc field with the contacts matching the address under the cursor listed beneath
 * it. Up and Down move through them, Enter or Tab takes one, and so does a tap.
 */
@Composable
internal fun RecipientInput(
    label: String,
    field: RecipientField,
    value: String,
    suggestions: Suggestions?,
    actions: RecipientActions,
    onShowCopies: (() -> Unit)? = null,
) {
    val colors = CorvidTheme.colors
    var typed by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val editing = if (typed.text == value) typed else TextFieldValue(value, TextRange(value.length))
    val shown = suggestions?.takeIf { it.field == field }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) {
            Text(label, style = CorvidTheme.type.name, color = colors.inkMuted, modifier = Modifier.width(LABEL_WIDTH))
            BasicTextField(
                value = editing,
                onValueChange = {
                    val changed = it.text != editing.text
                    typed = it
                    if (changed) actions.onEdit(field, it.text, it.selection.end)
                },
                singleLine = true,
                textStyle = CorvidTheme.type.name.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier =
                    Modifier
                        .weight(1f)
                        .onFocusChanged { if (!it.isFocused && shown != null) actions.onDismiss() }
                        .onPreviewKeyEvent { shown != null && it.steer(actions) },
            )
            onShowCopies?.let {
                Text(
                    stringResource(Res.string.compose_show_copies),
                    style = CorvidTheme.type.meta,
                    color = colors.inkMuted,
                    modifier = Modifier.clickable(onClick = it).padding(8.dp),
                )
            }
        }
        shown?.let { SuggestionList(it, onPick = actions.onAccept) }
        HorizontalDivider(color = colors.line)
    }
}

private fun KeyEvent.steer(actions: RecipientActions): Boolean {
    if (type != KeyEventType.KeyDown) return key in STEERING_KEYS
    when (key) {
        Key.DirectionDown -> actions.onMove(1)
        Key.DirectionUp -> actions.onMove(-1)
        Key.Enter, Key.NumPadEnter, Key.Tab -> actions.onAccept(null)
        else -> return false
    }
    return true
}

private val STEERING_KEYS = setOf(Key.DirectionDown, Key.DirectionUp, Key.Enter, Key.NumPadEnter, Key.Tab)

@Composable
private fun SuggestionList(
    suggestions: Suggestions,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = LABEL_WIDTH, bottom = 6.dp)) {
        suggestions.contacts.forEachIndexed { index, contact ->
            SuggestionRow(contact, highlighted = index == suggestions.highlighted) { onPick(index) }
        }
    }
}

/** Rows never take focus, so a tap lands while the field still holds it. */
@Composable
private fun SuggestionRow(
    contact: Contact,
    highlighted: Boolean,
    onPick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val name = contact.address.name
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(CorvidShape.small)
            .background(if (highlighted) colors.hover else colors.surface)
            .focusProperties { canFocus = false }
            .clickable(onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            name ?: contact.address.email,
            style = CorvidTheme.type.name,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (name != null) {
            Text(
                contact.address.email,
                style = CorvidTheme.type.meta,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
