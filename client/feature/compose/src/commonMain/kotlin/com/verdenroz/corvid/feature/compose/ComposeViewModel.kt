package com.verdenroz.corvid.feature.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.SendResult
import com.verdenroz.corvid.core.data.SendUnavailable
import com.verdenroz.corvid.core.data.StartResult
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.parseAddresses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ComposeViewModel(
    private val composeRepository: ComposeRepository,
    private val intent: ComposeIntent,
) : ViewModel() {
    private val _state = MutableStateFlow<ComposeUiState>(ComposeUiState.Loading)
    val state: StateFlow<ComposeUiState> = _state

    init {
        viewModelScope.launch {
            try {
                _state.value =
                    when (val start = composeRepository.start(intent)) {
                        is StartResult.Ready -> ComposeUiState.Editing(start.draft.toForm(), start.draft.inReplyTo, start.draft.references)
                        is SendUnavailable -> ComposeUiState.Failed(start.message())
                    }
            } catch (e: JmapException) {
                _state.value = ComposeUiState.Failed("could not reach the server")
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
                when (val result = composeRepository.send(draft)) {
                    is SendResult.Sent -> _state.value = ComposeUiState.Sent
                    is SendResult.Rejected -> fail(result.reason)
                    is SendUnavailable -> fail(result.message())
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
}

private fun SendUnavailable.message() =
    when (this) {
        is SendUnavailable.NoIdentity -> "This account has no sending identity."
        is SendUnavailable.NoMailbox -> "This account has no $role mailbox."
    }

private fun Draft.toForm() =
    ComposeForm(
        to = to.joinToString(", ") { it.toText() },
        cc = cc.joinToString(", ") { it.toText() },
        bcc = bcc.joinToString(", ") { it.toText() },
        subject = subject,
        body = body,
    )

private fun ComposeForm.toDraft(
    inReplyTo: List<String>,
    references: List<String>,
) = Draft(
    to = parseAddresses(to),
    cc = parseAddresses(cc),
    bcc = parseAddresses(bcc),
    subject = subject,
    body = body,
    inReplyTo = inReplyTo,
    references = references,
)
