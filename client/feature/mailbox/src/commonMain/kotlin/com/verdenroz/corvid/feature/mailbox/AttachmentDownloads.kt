package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.Handoff
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.fileName
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.text.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What became of the last tap on one attachment: still fetching, handed to the platform, or never downloaded. */
sealed interface AttachmentState {
    data object Fetching : AttachmentState

    data class Done(val handoff: Handoff) : AttachmentState

    data class Unfetched(val message: UiText) : AttachmentState
}

/** Fetches attachments on demand and hands each to the platform; [states] is keyed by blob id. */
class AttachmentDownloads(
    private val repository: MailboxRepository,
    private val files: AttachmentFiles,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, AttachmentState>>(emptyMap())
    val states: StateFlow<Map<String, AttachmentState>> = _states

    /** A tap while the same file is still being fetched does nothing. */
    fun open(attachment: Attachment) {
        val before = _states.getAndUpdate { it + (attachment.blobId to AttachmentState.Fetching) }
        if (before[attachment.blobId] == AttachmentState.Fetching) return
        scope.launch {
            val state =
                try {
                    AttachmentState.Done(files.open(attachment.fileName(), attachment.type, repository.download(attachment)))
                } catch (e: JmapException) {
                    AttachmentState.Unfetched(e.userMessage())
                }
            _states.update { it + (attachment.blobId to state) }
        }
    }
}
