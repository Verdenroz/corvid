package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.MailMove
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiPlural
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.action_archived
import com.verdenroz.corvid.feature.mailbox.resources.action_deleted
import com.verdenroz.corvid.feature.mailbox.resources.action_partly_refused
import com.verdenroz.corvid.feature.mailbox.resources.action_refused
import com.verdenroz.corvid.feature.mailbox.resources.action_restored
import com.verdenroz.corvid.feature.mailbox.resources.action_trashed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.PluralStringResource

/** What the last action did, for the bar under the list; [undo] is null when there is nothing to put back. */
data class ActionNotice(val text: UiText, val undo: MailMove? = null)

/** The actions every list and the reader share; each list hides and refetches its own rows from [MailboxRepository.moves]. */
class MailActions(
    private val repository: MailboxRepository,
) : ViewModel() {
    private val _notice = MutableStateFlow<ActionNotice?>(null)
    val notice: StateFlow<ActionNotice?> = _notice

    fun archive(
        ids: Set<String>,
        conversations: Int,
    ) = moveAway(ids, conversations, Res.plurals.action_archived) { repository.archive(it) }

    fun trash(
        ids: Set<String>,
        conversations: Int,
    ) = moveAway(ids, conversations, Res.plurals.action_trashed) { repository.trash(it) }

    /** No undo: the server keeps nothing to restore. */
    fun destroy(
        ids: Set<String>,
        conversations: Int,
    ) = act {
        val refused = repository.destroy(ids)
        _notice.value = ActionNotice(refusal(refused, ids) ?: uiPlural(Res.plurals.action_deleted, conversations))
    }

    fun setRead(
        ids: Set<String>,
        read: Boolean,
    ) = keywords(ids, CorvidKeywords.SEEN, read)

    fun setStarred(
        ids: Set<String>,
        starred: Boolean,
    ) = keywords(ids, CorvidKeywords.FLAGGED, starred)

    fun undo() {
        val done = _notice.value?.undo ?: return
        act {
            val undone = repository.undo(done)
            _notice.value = ActionNotice(refusal(undone.refused, undone.before.keys) ?: uiText(Res.string.action_restored))
        }
    }

    fun dismiss() {
        _notice.value = null
    }

    private fun moveAway(
        ids: Set<String>,
        conversations: Int,
        done: PluralStringResource,
        move: suspend (Set<String>) -> MailMove,
    ) = act {
        val moved = move(ids)
        val text = refusal(moved.refused, ids) ?: uiPlural(done, conversations)
        _notice.value = ActionNotice(text, moved.takeIf { it.moved.isNotEmpty() })
    }

    private fun act(block: suspend () -> Unit) {
        _notice.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: JmapException) {
                _notice.value = ActionNotice(e.userMessage())
            }
        }
    }

    private fun keywords(
        ids: Set<String>,
        keyword: String,
        set: Boolean,
    ) {
        viewModelScope.launch {
            try {
                val refused = repository.updateKeywords(ids.toList(), mapOf(keyword to set))
                refusal(refused, ids)?.let { _notice.value = ActionNotice(it) }
            } catch (e: JmapException) {
                _notice.value = ActionNotice(e.userMessage())
            }
        }
    }

    private fun refusal(
        refused: Map<String, String>,
        ids: Collection<String>,
    ): UiText? =
        when {
            refused.isEmpty() -> null
            refused.size == ids.size -> uiText(Res.string.action_refused, UiText.FromServer(refused.values.first()))
            else -> uiText(Res.string.action_partly_refused, refused.size, UiText.FromServer(refused.values.first()))
        }
}
