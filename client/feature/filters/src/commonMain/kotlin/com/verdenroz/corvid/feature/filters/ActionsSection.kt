package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_add_action
import com.verdenroz.corvid.feature.filters.resources.filters_choose_folder
import com.verdenroz.corvid.feature.filters.resources.filters_label
import com.verdenroz.corvid.feature.filters.resources.filters_remove
import org.jetbrains.compose.resources.stringResource

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
    PillButton(
        stringResource(Res.string.filters_add_action),
        CorvidIcons.Plus,
        { onChange(actions + FilterAction.MarkRead) },
        height = 32.dp,
    )
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
            if (canRemove) PillButton(stringResource(Res.string.filters_remove), null, onRemove, height = 32.dp)
        }
        when (action) {
            is FilterAction.MoveToFolder ->
                Picker(
                    selected =
                        if (action.folder.path.isEmpty()) {
                            stringResource(
                                Res.string.filters_choose_folder,
                            )
                        } else {
                            action.folder.displayPath()
                        },
                    options = folders,
                    label = { it.displayPath() },
                ) { onChange(FilterAction.MoveToFolder(it)) }
            is FilterAction.AddLabel ->
                CorvidTextField(
                    action.label,
                    { onChange(FilterAction.AddLabel(it)) },
                    stringResource(Res.string.filters_label),
                    Modifier.fillMaxWidth(),
                )
            else -> Unit
        }
    }
}
