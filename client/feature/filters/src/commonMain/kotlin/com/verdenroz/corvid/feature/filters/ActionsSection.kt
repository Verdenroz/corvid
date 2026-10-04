package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.MailboxRef

@Composable
internal fun ActionsSection(
    actions: List<FilterAction>,
    folders: List<MailboxRef>,
    onChange: (List<FilterAction>) -> Unit,
) {
    actions.forEachIndexed { index, action ->
        ActionRow(
            action = action,
            folders = folders,
            canRemove = actions.size > 1,
            onChange = { onChange(actions.replaced(index, it)) },
            onRemove = { onChange(actions.removedAt(index)) },
        )
    }
    TextButton(onClick = { onChange(actions + FilterAction.MarkRead) }) { Text("Add action") }
}

@Composable
private fun ActionRow(
    action: FilterAction,
    folders: List<MailboxRef>,
    canRemove: Boolean,
    onChange: (FilterAction) -> Unit,
    onRemove: () -> Unit,
) {
    val kinds =
        listOf(
            FilterAction.MoveToFolder(folders.firstOrNull() ?: MailboxRef.None),
            FilterAction.AddLabel(""),
            FilterAction.MarkRead,
            FilterAction.Star,
            FilterAction.MoveToTrash,
        )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(action.label(), kinds, { it.label() }) { onChange(it) }
            if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
        }
        when (action) {
            is FilterAction.MoveToFolder ->
                Picker(
                    selected = if (action.folder.path.isEmpty()) "Choose a folder" else action.folder.displayPath(),
                    options = folders,
                    label = { it.displayPath() },
                ) { onChange(FilterAction.MoveToFolder(it)) }
            is FilterAction.AddLabel ->
                OutlinedTextField(
                    value = action.label,
                    onValueChange = { onChange(FilterAction.AddLabel(it)) },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
            else -> Unit
        }
    }
}
