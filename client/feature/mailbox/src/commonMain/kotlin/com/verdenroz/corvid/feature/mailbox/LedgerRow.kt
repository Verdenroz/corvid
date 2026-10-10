package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.clockTime
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.labels
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.core.ui.provenance.TrackerCount
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.ledger_thread_messages
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_separator
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LedgerRow(
    conversation: Conversation,
    zone: TimeZone,
    wide: Boolean,
    onClick: () -> Unit,
    onOpenSender: (String) -> Unit,
) {
    val colors = CorvidTheme.colors
    val email = conversation.latest
    val openSender = email.from?.email?.let { { onOpenSender(it) } }
    val suspect = email.provenance.suspect
    Column(Modifier.fillMaxWidth().background(if (suspect) colors.dangerTint else colors.surface).clickable(onClick = onClick)) {
        if (wide) WideRow(conversation, zone, openSender) else NarrowRow(conversation, zone, openSender)
        HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun WideRow(
    conversation: Conversation,
    zone: TimeZone,
    openSender: (() -> Unit)?,
) {
    val email = conversation.latest
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Time(conversation, zone, Modifier.width(58.dp))
        Avatar(email, openSender)
        SenderName(conversation, Modifier.width(200.dp), openSender)
        Summary(conversation, Modifier.weight(1f))
        (email.provenance.rewrite as? Rewrite.Stripped)?.takeIf { it.stripped > 0 }?.let { TrackerCount(it.stripped) }
    }
}

@Composable
private fun NarrowRow(
    conversation: Conversation,
    zone: TimeZone,
    openSender: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
        Avatar(conversation.latest, openSender)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SenderName(conversation, Modifier.weight(1f), openSender)
                Time(conversation, zone, Modifier.padding(start = 8.dp, top = 2.dp))
            }
            Summary(conversation, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Time(
    conversation: Conversation,
    zone: TimeZone,
    modifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        modifier = modifier,
    ) {
        if (conversation.unread) Box(Modifier.size(7.dp).background(CorvidTheme.colors.accent, CircleShape))
        Text(
            clockTime(conversation.latest.receivedAt, zone).asString(),
            style = CorvidTheme.type.metaNumeric,
            color = CorvidTheme.colors.inkFaint,
        )
    }
}

@Composable
private fun Avatar(
    email: EmailSummary,
    openSender: (() -> Unit)?,
) {
    SenderAvatar(
        name = email.from.displayName(),
        sender = email.provenance.sender,
        seal = email.provenance.seal,
        size = 40.dp,
        onClick = openSender,
    )
}

@Composable
private fun SenderName(
    conversation: Conversation,
    modifier: Modifier,
    openSender: (() -> Unit)?,
) {
    val email = conversation.latest
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                email.from.displayName(),
                modifier = (if (openSender == null) Modifier else Modifier.clickable(onClick = openSender)).weight(1f, fill = false),
                style = CorvidTheme.type.name,
                fontWeight = if (conversation.unread) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conversation.messageCount > 1) MessageCount(conversation.messageCount)
        }
        email.provenance.sender.sourceLabel()?.let {
            Text(it, style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MessageCount(count: Int) {
    val description = pluralStringResource(Res.plurals.ledger_thread_messages, count, count)
    Text(
        count.toString(),
        style = CorvidTheme.type.metaNumeric,
        color = CorvidTheme.colors.inkFaint,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun Summary(
    conversation: Conversation,
    modifier: Modifier,
) {
    val email = conversation.latest
    val colors = CorvidTheme.colors
    val subjectColor = if (email.provenance.suspect) colors.danger else colors.ink
    val subject = email.subject.ifEmpty { stringResource(Res.string.mailbox_no_subject) }
    val separator = stringResource(Res.string.mailbox_separator)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        email.labels.forEach { LabelChip(it) }
        Text(
            buildAnnotatedString {
                withStyle(
                    SpanStyle(color = subjectColor, fontWeight = if (conversation.unread) FontWeight.Bold else FontWeight.Medium),
                ) { append(subject) }
                if (email.preview.isNotBlank()) append(" $separator ${email.preview}")
            },
            style = CorvidTheme.type.name.copy(fontWeight = FontWeight.Normal),
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
