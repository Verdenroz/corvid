package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.fileName
import com.verdenroz.corvid.core.data.fileSize
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.AttachmentTile
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.attachment_fetching
import com.verdenroz.corvid.feature.mailbox.resources.attachment_keep_closed
import com.verdenroz.corvid.feature.mailbox.resources.attachment_open
import com.verdenroz.corvid.feature.mailbox.resources.attachment_open_anyway
import com.verdenroz.corvid.feature.mailbox.resources.attachment_open_question
import com.verdenroz.corvid.feature.mailbox.resources.attachment_saved
import com.verdenroz.corvid.feature.mailbox.resources.attachment_unverified
import org.jetbrains.compose.resources.stringResource

/** A message's files under its body. Mail that failed authentication says so, and each file asks once more before it opens. */
@Composable
internal fun AttachmentStrip(
    email: EmailDetail,
    states: Map<String, AttachmentState>,
    onOpen: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (email.attachments.isEmpty()) return
    val colors = CorvidTheme.colors
    val suspect = email.provenance.suspect
    var asking by remember(email.id) { mutableStateOf<Attachment?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (suspect) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(CorvidIcons.Alert, contentDescription = null, tint = colors.danger, modifier = Modifier.size(14.dp))
                Text(stringResource(Res.string.attachment_unverified), style = CorvidTheme.type.meta, color = colors.danger)
            }
        }
        asking?.let { file ->
            DiscardQuestion(
                stringResource(Res.string.attachment_open_question, file.fileName()),
                onConfirm = {
                    asking = null
                    onOpen(file)
                },
                onCancel = { asking = null },
                confirmLabel = stringResource(Res.string.attachment_open_anyway),
                cancelLabel = stringResource(Res.string.attachment_keep_closed),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            email.attachments.forEach { file ->
                val state = states[file.blobId]
                AttachmentTile(
                    name = file.name,
                    type = file.type,
                    detail = detail(file, state),
                    detailColor = if (state is AttachmentState.Failed) colors.danger else colors.inkMuted,
                    onClick = { if (suspect) asking = file else onOpen(file) },
                    onClickLabel = stringResource(Res.string.attachment_open),
                )
            }
        }
    }
}

@Composable
private fun detail(
    file: Attachment,
    state: AttachmentState?,
): String =
    when (state) {
        is AttachmentState.Fetching -> stringResource(Res.string.attachment_fetching)
        is AttachmentState.Saved -> stringResource(Res.string.attachment_saved, state.location)
        is AttachmentState.Failed -> state.message.asString()
        is AttachmentState.Opened, null -> fileSize(file.size).asString()
    }
