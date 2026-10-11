package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.graphics.vector.ImageVector
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.action_archive
import com.verdenroz.corvid.feature.mailbox.resources.action_destroy
import com.verdenroz.corvid.feature.mailbox.resources.action_mark_read
import com.verdenroz.corvid.feature.mailbox.resources.action_mark_unread
import com.verdenroz.corvid.feature.mailbox.resources.action_not_spam
import com.verdenroz.corvid.feature.mailbox.resources.action_report_spam
import com.verdenroz.corvid.feature.mailbox.resources.action_star
import com.verdenroz.corvid.feature.mailbox.resources.action_trash
import com.verdenroz.corvid.feature.mailbox.resources.action_unstar
import org.jetbrains.compose.resources.StringResource

/** [moves] files the conversation somewhere else, so Move to sits beside it. */
enum class ConversationAction(val label: StringResource, val icon: ImageVector, val moves: Boolean = false) {
    Archive(Res.string.action_archive, CorvidIcons.Archive, moves = true),
    Trash(Res.string.action_trash, CorvidIcons.Trash, moves = true),
    Destroy(Res.string.action_destroy, CorvidIcons.Trash, moves = true),
    ReportSpam(Res.string.action_report_spam, CorvidIcons.Spam, moves = true),
    NotSpam(Res.string.action_not_spam, CorvidIcons.Inbox, moves = true),
    MarkRead(Res.string.action_mark_read, CorvidIcons.MailOpen),
    MarkUnread(Res.string.action_mark_unread, CorvidIcons.Mail),
    Star(Res.string.action_star, CorvidIcons.Star),
    Unstar(Res.string.action_unstar, CorvidIcons.Star),
}

/** True for an action that takes the conversation out of the list it was taken from. */
val ConversationAction.leavesList: Boolean
    get() = moves || this == ConversationAction.MarkUnread

/** What a conversation's controls ask for: one of its [ConversationAction]s, or a move to a folder picked from Move to. */
sealed interface Act {
    val moves: Boolean
    val leavesList: Boolean

    data class Do(val action: ConversationAction) : Act {
        override val moves get() = action.moves
        override val leavesList get() = action.leavesList
    }

    data class Move(val folder: MoveFolder) : Act {
        override val moves get() = true
        override val leavesList get() = true
    }
}

/**
 * The messages an action reaches: moves take [all] of each thread, as a row stands for the whole
 * conversation; unread and star take the [latest] alone; read takes only the [unread].
 */
class ActionTarget(
    val all: Set<String>,
    val latest: Set<String>,
    val unread: Set<String>,
    val conversations: Int,
    val starred: Boolean,
)

fun Collection<Conversation>.target() =
    ActionTarget(
        all = flatMap { it.emailIds }.toSet(),
        latest = map { it.latest.id }.toSet(),
        unread = flatMap { it.unreadIds }.toSet(),
        conversations = size,
        starred = isNotEmpty() && all { CorvidKeywords.FLAGGED in it.latest.keywords },
    )

/** The reader has marked the thread read by the time it shows, so [ActionTarget.unread] is empty. */
fun ReaderUiState.Loaded.target() =
    ActionTarget(
        all = messages.map { it.id }.toSet(),
        latest = setOf(newest.id),
        unread = emptySet(),
        conversations = 1,
        starred = CorvidKeywords.FLAGGED in newest.keywords,
    )

/** What a [kind] list offers for [target], in the order the buttons are drawn. */
fun actionsFor(
    kind: ListKind,
    target: ActionTarget,
): List<ConversationAction> {
    val read = if (target.unread.isNotEmpty()) ConversationAction.MarkRead else ConversationAction.MarkUnread
    val star = if (target.starred) ConversationAction.Unstar else ConversationAction.Star
    return movesFor(kind) + read + listOfNotNull(star.takeIf { kind == ListKind.Inbox || kind == ListKind.Other })
}

/** The moves a [kind] list offers whatever is ticked, which the swipes draw from too. */
internal fun movesFor(kind: ListKind): List<ConversationAction> =
    when (kind) {
        ListKind.Inbox -> listOf(ConversationAction.Archive, ConversationAction.Trash, ConversationAction.ReportSpam)
        ListKind.Trash -> listOf(ConversationAction.Destroy)
        ListKind.Junk -> listOf(ConversationAction.NotSpam, ConversationAction.Destroy)
        ListKind.Other -> listOf(ConversationAction.Trash, ConversationAction.ReportSpam)
    }

/** Deleting for good, the one act that waits on a question before it runs. */
internal val DESTROY: Act = Act.Do(ConversationAction.Destroy)

fun MailActions.perform(
    act: Act,
    target: ActionTarget,
) {
    when (act) {
        is Act.Do -> perform(act.action, target)
        is Act.Move -> moveTo(target.all, target.conversations, act.folder)
    }
}

private fun MailActions.perform(
    action: ConversationAction,
    target: ActionTarget,
) {
    when (action) {
        ConversationAction.Archive -> archive(target.all, target.conversations)
        ConversationAction.Trash -> trash(target.all, target.conversations)
        ConversationAction.Destroy -> destroy(target.all, target.conversations)
        ConversationAction.ReportSpam -> reportSpam(target.all, target.conversations)
        ConversationAction.NotSpam -> notSpam(target.all, target.conversations)
        ConversationAction.MarkRead -> setRead(target.unread, read = true)
        ConversationAction.MarkUnread -> setRead(target.latest, read = false)
        ConversationAction.Star -> setStarred(target.latest, starred = true)
        ConversationAction.Unstar -> setStarred(target.all, starred = false)
    }
}
