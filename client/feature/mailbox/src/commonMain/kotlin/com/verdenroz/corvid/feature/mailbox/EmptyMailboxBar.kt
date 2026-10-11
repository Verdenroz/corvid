package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.JUNK
import com.verdenroz.corvid.core.data.TRASH
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.action_empty_junk
import com.verdenroz.corvid.feature.mailbox.resources.action_empty_question
import com.verdenroz.corvid.feature.mailbox.resources.action_empty_trash
import org.jetbrains.compose.resources.stringResource

/** Trash and Junk alone get a way to delete everything in them, behind the same question as one deletion. */
@Composable
internal fun EmptyMailboxBar(
    tab: LedgerTab,
    onEmpty: (String) -> Unit,
) {
    val (label, role) =
        when (tab) {
            LedgerTab.Trash -> Res.string.action_empty_trash to TRASH
            LedgerTab.Junk -> Res.string.action_empty_junk to JUNK
            else -> return
        }
    var confirming by rememberSaveable(tab) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (confirming) {
            DestroyQuestion(
                onConfirm = {
                    confirming = false
                    onEmpty(role)
                },
                onCancel = { confirming = false },
                question = stringResource(Res.string.action_empty_question),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(horizontal = CorvidSpacing.gutter(LocalWideLayout.current), vertical = 8.dp),
            ) {
                PillButton(stringResource(label), CorvidIcons.Trash, { confirming = true }, height = 32.dp)
            }
        }
        HorizontalDivider(color = CorvidTheme.colors.line)
    }
}
