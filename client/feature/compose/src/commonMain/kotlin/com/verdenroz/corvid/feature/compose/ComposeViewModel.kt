package com.verdenroz.corvid.feature.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.DraftSaveResult
import com.verdenroz.corvid.core.data.SendResult
import com.verdenroz.corvid.core.data.SendUnavailable
import com.verdenroz.corvid.core.data.StartResult
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.parseAddresses
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_add_recipient
import com.verdenroz.corvid.feature.compose.resources.compose_draft_gone
import com.verdenroz.corvid.feature.compose.resources.compose_no_drafts_mailbox
import com.verdenroz.corvid.feature.compose.resources.compose_no_identity
import com.verdenroz.corvid.feature.compose.resources.compose_no_sent_mailbox
import com.verdenroz.corvid.feature.compose.resources.compose_not_sent
import com.verdenroz.corvid.feature.compose.resources.compose_original_gone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [restored] is the form as the user left it before the screen was recreated; it wins over the fresh draft. */
class ComposeViewModel(
    private val composeRepository: ComposeRepository,
    private val intent: ComposeIntent,
    private val restored: ComposeForm? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<ComposeUiState>(ComposeUiState.Loading)
    val state: StateFlow<ComposeUiState> = _state

    init {
        viewModelScope.launch {
            try {
                _state.value =
                    when (val start = composeRepository.start(intent)) {
                        is StartResult.Ready -> ComposeUiState.Editing(seed = start.draft, form = restored ?: start.draft.toForm())
                        is StartResult.DraftGone -> ComposeUiState.Failed(uiText(Res.string.compose_draft_gone))
                        is StartResult.OriginalGone -> ComposeUiState.Failed(uiText(Res.string.compose_original_gone))
                        is SendUnavailable -> ComposeUiState.Failed(start.message())
                    }
            } catch (e: JmapException) {
                _state.value = ComposeUiState.Failed(e.userMessage())
            }
        }
    }

    fun edit(change: ComposeForm.() -> ComposeForm) {
        val current = _state.value as? ComposeUiState.Editing ?: return
        _state.value = current.copy(form = current.form.change())
    }

    /**
     * Whether the screen may close now. Unsent text is not kept anywhere, so the first back on an
     * edited form asks instead, and a second back means discard.
     */
    fun requestClose(): Boolean {
        val current = _state.value as? ComposeUiState.Editing ?: return true
        if (current.confirmingDiscard || current.form == current.seed.toForm()) return true
        _state.value = current.copy(confirmingDiscard = true)
        return false
    }

    fun keepEditing() {
        val current = _state.value as? ComposeUiState.Editing ?: return
        _state.value = current.copy(confirmingDiscard = false)
    }

    fun send() {
        val current = _state.value as? ComposeUiState.Editing ?: return
        if (current.sending) return
        val draft = current.form.applyTo(current.seed)
        if (draft.to.isEmpty()) {
            _state.value = current.copy(error = uiText(Res.string.compose_add_recipient))
            return
        }
        _state.value = current.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                when (val saved = composeRepository.save(draft, replacing = null)) {
                    is DraftSaveResult.Saved ->
                        when (val result = composeRepository.send(saved.emailId)) {
                            is SendResult.Sent -> _state.value = ComposeUiState.Sent
                            is SendResult.Rejected -> fail(UiText.FromServer(result.reason))
                            is SendUnavailable -> fail(result.message())
                        }
                    is DraftSaveResult.Rejected -> fail(UiText.FromServer(saved.reason))
                    is SendUnavailable -> fail(saved.message())
                }
            } catch (e: JmapException) {
                fail(e.userMessage())
            }
        }
    }

    private fun fail(reason: UiText) {
        val current = _state.value as ComposeUiState.Editing
        _state.value = current.copy(sending = false, error = uiText(Res.string.compose_not_sent, reason))
    }
}

private fun SendUnavailable.message() =
    when (this) {
        is SendUnavailable.NoIdentity -> uiText(Res.string.compose_no_identity)
        is SendUnavailable.NoMailbox ->
            uiText(
                if (role == "drafts") Res.string.compose_no_drafts_mailbox else Res.string.compose_no_sent_mailbox,
            )
    }

private fun Draft.toForm() =
    ComposeForm(
        to = to.joinToString(", ") { it.toText() },
        cc = cc.joinToString(", ") { it.toText() },
        bcc = bcc.joinToString(", ") { it.toText() },
        subject = subject,
        body = body,
    )

private fun ComposeForm.applyTo(seed: Draft) =
    seed.copy(
        to = parseAddresses(to),
        cc = parseAddresses(cc),
        bcc = parseAddresses(bcc),
        subject = subject,
        body = body,
    )
