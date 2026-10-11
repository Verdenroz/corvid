package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.clockTime
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.labels
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.core.ui.provenance.TrackerCount
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.has_attachments
import com.verdenroz.corvid.feature.mailbox.resources.ledger_thread_messages
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_separator
import com.verdenroz.corvid.feature.mailbox.resources.selection_select
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val FOCUS_BAR = 3.dp

/**
 * While selecting, the whole row ticks. Otherwise a tap opens the conversation, a long press anywhere ticks it, and the
 * avatar ticks on narrow (Gmail's habit) but opens the sender's card on wide, where the name does too and hover shows the tick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LedgerRow(
    conversation: Conversation,
    zone: TimeZone,
    wide: Boolean,
    actions: List<ConversationAction>,
    selected: Boolean,
    selecting: Boolean,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAction: (ConversationAction, Conversation) -> Unit,
    menu: MoveMenu,
    onMove: (MoveFolder, Conversation) -> Unit,
    onOpenSender: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    val email = conversation.latest
    val openSender = email.from?.email?.let { { onOpenSender(it) } }
    val toggle = { onToggle(email.id) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuShown by remember { mutableStateOf(false) }
    val focused by interaction.collectIsFocusedAsState()
    val background =
        when {
            selected -> colors.accentSoft
            email.provenance.suspect -> colors.dangerTint
            hovered || focused -> colors.hover
            else -> colors.surface
        }
    val accent = colors.accent
    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .then(if (focused) Modifier.drawBehind { drawRect(accent, size = Size(FOCUS_BAR.toPx(), size.height)) } else Modifier)
            .hoverable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClickLabel = stringResource(Res.string.selection_select),
                onLongClick = toggle,
                onClick = if (selecting) toggle else ({ onOpen(email.id) }),
            ),
    ) {
        val tick = selecting || (wide && (hovered || focused))
        val leadingTap = if (tick || !wide) toggle else (openSender ?: toggle)
        val nameTap = openSender?.takeIf { wide && !selecting }
        val leading: @Composable () -> Unit = {
            Leading(email, tick, selected, if (wide) 40.dp else 48.dp, leadingTap, toggle)
        }
        if (wide) {
            val trailing: @Composable () -> Unit = {
                if ((hovered || focused || menuShown) && !selecting) {
                    ConversationActionRow(
                        actions,
                        menu,
                        { onMove(it, conversation) },
                        size = 34.dp,
                        onMenuShown = { menuShown = it },
                    ) { onAction(it, conversation) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (CorvidKeywords.FLAGGED in email.keywords) StarMark()
                        (email.provenance.rewrite as? Rewrite.Stripped)?.takeIf { it.stripped > 0 }?.let { TrackerCount(it.stripped) }
                    }
                }
            }
            WideRow(conversation, zone, nameTap, toggle, leading, trailing)
        } else {
            NarrowRow(conversation, zone, leading)
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun WideRow(
    conversation: Conversation,
    zone: TimeZone,
    openSender: (() -> Unit)?,
    onLongPress: () -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Time(conversation, zone, Modifier.width(58.dp))
        leading()
        SenderName(conversation, Modifier.width(200.dp), openSender, onLongPress)
        Summary(conversation, Modifier.weight(1f))
        trailing()
    }
}

/** The leading target is 48dp, 4dp wider than the avatar on each side, so the padding gives those 4dp back. */
@Composable
private fun NarrowRow(
    conversation: Conversation,
    zone: TimeZone,
    leading: @Composable () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 7.dp, bottom = 11.dp),
    ) {
        leading()
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SenderName(conversation, Modifier.weight(1f), openSender = null, onLongPress = null)
                if (CorvidKeywords.FLAGGED in conversation.latest.keywords) StarMark(Modifier.padding(start = 6.dp, top = 3.dp))
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

/** The ripple is clipped to a circle over the avatar or tick, and the seal badge is left unclipped beside it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Leading(
    email: EmailSummary,
    tick: Boolean,
    selected: Boolean,
    size: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val select = stringResource(Res.string.selection_select)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        if (tick) {
            TickRing(selected)
        } else {
            SenderAvatar(name = email.from.displayName(), sender = email.provenance.sender, seal = email.provenance.seal, size = 40.dp)
        }
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .focusProperties { canFocus = false }
                .combinedClickable(onLongClickLabel = select, onLongClick = onLongPress, onClick = onTap)
                .semantics {
                    if (tick) {
                        contentDescription = select
                        role = Role.Checkbox
                        this.selected = selected
                    }
                },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SenderName(
    conversation: Conversation,
    modifier: Modifier,
    openSender: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val email = conversation.latest
    val target =
        if (openSender == null) {
            Modifier
        } else {
            Modifier
                .focusProperties { canFocus = false }
                .combinedClickable(interactionSource = null, indication = null, onLongClick = onLongPress, onClick = openSender)
        }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                email.from.displayName(),
                modifier = target.weight(1f, fill = false),
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
            modifier = Modifier.weight(1f, fill = false),
        )
        if (email.hasAttachment) {
            Icon(CorvidIcons.Paperclip, stringResource(Res.string.has_attachments), tint = colors.inkFaint, modifier = Modifier.size(14.dp))
        }
    }
}
