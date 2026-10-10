package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.Correction
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.core.ui.provenance.sealDescription
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_forward
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_reply
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_reply_all
import com.verdenroz.corvid.feature.mailbox.resources.reader_cc
import com.verdenroz.corvid.feature.mailbox.resources.reader_to
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** One message of a thread: in full with its own custody and teach controls, or as a summary line. A suspect one is tinted alone. */
@Composable
internal fun ThreadMessage(
    email: EmailDetail,
    repository: MailboxRepository,
    expanded: Boolean,
    newest: Boolean,
    threaded: Boolean,
    wide: Boolean,
    teach: TeachState,
    onToggle: (String) -> Unit,
    onAnswer: (String, Correction) -> Unit,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
) {
    val colors = CorvidTheme.colors
    val toggle = remember(email.id, onToggle) { { onToggle(email.id) } }
    val answer = remember(email.id, onAnswer) { { correction: Correction -> onAnswer(email.id, correction) } }
    val tint = if (email.provenance.suspect) Modifier.background(colors.dangerTint) else Modifier
    val gutter = CorvidSpacing.readingGutter(wide)
    Column(Modifier.fillMaxWidth().then(tint)) {
        Column(Modifier.padding(horizontal = gutter).widthIn(max = 860.dp)) {
            if (expanded) {
                ExpandedMessage(email, repository, newest, threaded, wide, teach, toggle, answer, onCompose, onOpenSender)
            } else {
                CollapsedMessage(email, toggle)
            }
        }
        if (threaded) HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun ExpandedMessage(
    email: EmailDetail,
    repository: MailboxRepository,
    newest: Boolean,
    threaded: Boolean,
    wide: Boolean,
    teach: TeachState,
    onToggle: () -> Unit,
    onAnswer: (Correction) -> Unit,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 16.dp)) {
        SenderLine(email, onOpenSender, if (threaded) Modifier.clickable(onClick = onToggle) else Modifier)
        Custody(email, wide) { guess -> TeachControls(guess, teach.answering, teach.error, onAnswer) }
        MessageBody(email, repository, Modifier.padding(top = 18.dp).widthIn(max = 640.dp))
        if (newest) Replies(email, onCompose) else QuietReplies(email, onCompose)
    }
}

private class ReplyAction(val label: StringResource, val icon: ImageVector, val intent: ComposeIntent)

private fun replyActions(email: EmailDetail) =
    listOf(
        ReplyAction(Res.string.mailbox_reply, CorvidIcons.Reply, ComposeIntent.Reply(email.id, all = false)),
        ReplyAction(Res.string.mailbox_reply_all, CorvidIcons.ReplyAll, ComposeIntent.Reply(email.id, all = true)),
        ReplyAction(Res.string.mailbox_forward, CorvidIcons.Forward, ComposeIntent.Forward(email.id)),
    )

@Composable
private fun Replies(
    email: EmailDetail,
    onCompose: (ComposeIntent) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 24.dp),
    ) {
        replyActions(email).forEach { PillButton(stringResource(it.label), it.icon, { onCompose(it.intent) }) }
    }
}

/** The same three actions as icons, for a message that is not the newest. */
@Composable
private fun QuietReplies(
    email: EmailDetail,
    onCompose: (ComposeIntent) -> Unit,
) {
    Row(Modifier.padding(top = 8.dp)) {
        replyActions(email).forEach {
            IconButton({ onCompose(it.intent) }) { Icon(it.icon, stringResource(it.label), tint = CorvidTheme.colors.inkMuted) }
        }
    }
}

@Composable
private fun CollapsedMessage(
    email: EmailDetail,
    onToggle: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val suspect = email.provenance.suspect
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 12.dp),
    ) {
        SenderAvatar(email.from.displayName(), email.provenance.sender, email.provenance.seal, size = 28.dp)
        Text(
            email.from.displayName(),
            style = CorvidTheme.type.name,
            fontWeight = FontWeight.Medium,
            color = if (suspect) colors.danger else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Text(
            email.preview,
            style = CorvidTheme.type.name.copy(fontWeight = FontWeight.Normal),
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(stamp(email.receivedAt, zone, Clock.System.now()).asString(), style = CorvidTheme.type.meta, color = colors.inkFaint)
    }
}

@Composable
private fun SenderLine(
    email: EmailDetail,
    onOpenSender: (String) -> Unit,
    modifier: Modifier,
) {
    val open = email.from?.email?.let { { onOpenSender(it) } }
    val colors = CorvidTheme.colors
    val seal = email.provenance.seal
    val zone = remember { TimeZone.currentSystemDefault() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        SenderAvatar(email.from.displayName(), email.provenance.sender, seal, onClick = open)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    email.from.displayName(),
                    modifier = if (open == null) Modifier else Modifier.clickable(onClick = open),
                    style = CorvidTheme.type.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (seal) {
                    is Seal.Signed -> Icon(CorvidIcons.Seal, sealDescription(seal), tint = colors.accent, modifier = Modifier.size(15.dp))
                    is Seal.Failed -> Icon(CorvidIcons.Alert, sealDescription(seal), tint = colors.danger, modifier = Modifier.size(15.dp))
                    is Seal.Unsigned -> Unit
                }
                email.from?.email?.takeIf { email.from?.name != null || email.provenance.suspect }?.let {
                    Text(
                        senderAddress(it, email.provenance.suspect).asString(),
                        style = CorvidTheme.type.meta,
                        color = if (email.provenance.suspect) colors.danger else colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RecipientLine(Res.string.reader_to, email.to)
            RecipientLine(Res.string.reader_cc, email.cc)
        }
        Text(stamp(email.receivedAt, zone, Clock.System.now()).asString(), style = CorvidTheme.type.meta, color = colors.inkMuted)
    }
}

@Composable
private fun RecipientLine(
    line: StringResource,
    recipients: List<EmailAddress>,
) {
    if (recipients.isEmpty()) return
    Text(
        stringResource(line, recipients.map { it.displayName() }.joinToString()),
        style = CorvidTheme.type.meta,
        color = CorvidTheme.colors.inkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
