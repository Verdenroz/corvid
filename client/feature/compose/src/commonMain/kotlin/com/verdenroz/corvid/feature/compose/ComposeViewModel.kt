package com.verdenroz.corvid.feature.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.EmailLookup
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SendResult
import com.verdenroz.corvid.core.data.forwardDraft
import com.verdenroz.corvid.core.data.replyDraft
import com.verdenroz.corvid.core.data.splitAddresses
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ComposeViewModel(
    private val composeRepository: ComposeRepository,
    private val mailboxRepository: MailboxRepository,
    private val mode: ComposeMode,
) : ViewModel() {
    private val _state = MutableStateFlow<ComposeUiState>(ComposeUiState.Loading)
    val state: StateFlow<ComposeUiState> = _state
    private lateinit var identity: Identity

    init {
        viewModelScope.launch {
            try {
                identity = composeRepository.identity()
                val draft = initialDraft()
                _state.value =
                    if (draft == null) {
                        ComposeUiState.Gone
                    } else {
                        ComposeUiState.Editing(draft.toForm(), draft.inReplyTo, draft.references)
                    }
            } catch (e: JmapException) {
                _state.value = ComposeUiState.Failed((e.error as? JmapError.Request)?.detail ?: "could not reach the server")
            }
        }
    }

    fun edit(form: ComposeForm) {
        val current = _state.value as? ComposeUiState.Editing ?: return
        _state.value = current.copy(form = form)
    }

    fun send() {
        val current = _state.value as? ComposeUiState.Editing ?: return
        if (current.sending) return
        val draft = current.form.toDraft(current.inReplyTo, current.references)
        if (draft.to.isEmpty()) {
            _state.value = current.copy(error = "Add a recipient.")
            return
        }
        _state.value = current.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                when (val result = composeRepository.send(identity, draft)) {
                    is SendResult.Sent -> _state.value = ComposeUiState.Sent
                    is SendResult.Rejected -> fail(result.reason)
                }
            } catch (e: JmapException) {
                fail("could not reach the server")
            }
        }
    }

    private fun fail(reason: String) {
        val current = _state.value as ComposeUiState.Editing
        _state.value = current.copy(sending = false, error = "Not sent: $reason")
    }

    private suspend fun initialDraft(): Draft? =
        when (mode) {
            is ComposeMode.New -> Draft()
            is ComposeMode.Reply -> original(mode.emailId)?.let { replyDraft(it, identity.email, mode.all) }
            is ComposeMode.Forward -> original(mode.emailId)?.let(::forwardDraft)
        }

    private suspend fun original(emailId: String) = (mailboxRepository.getEmail(emailId) as? EmailLookup.Found)?.detail
}

private fun Draft.toForm() =
    ComposeForm(
        to = to.joinToString(", "),
        cc = cc.joinToString(", "),
        bcc = bcc.joinToString(", "),
        subject = subject,
        body = body,
    )

private fun ComposeForm.toDraft(
    inReplyTo: List<String>,
    references: List<String>,
) = Draft(
    to = splitAddresses(to),
    cc = splitAddresses(cc),
    bcc = splitAddresses(bcc),
    subject = subject,
    body = body,
    inReplyTo = inReplyTo,
    references = references,
)
