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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * [restored] is the form as the user left it before the screen was recreated; it wins over the
 * fresh draft, and the server draft it names, if any, is the one it keeps replacing.
 */
class ComposeViewModel(
    private val composeRepository: ComposeRepository,
    private val intent: ComposeIntent,
    private val restored: ComposeRestore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<ComposeUiState>(ComposeUiState.Loading)
    val state: StateFlow<ComposeUiState> = _state

    private val saves = Mutex()
    private var pendingSave: Job? = null
    private var discarded = false

    init {
        viewModelScope.launch {
            val target = restored?.draftId?.let(ComposeIntent::Draft) ?: intent
            try {
                _state.value =
                    when (val start = composeRepository.start(target)) {
                        is StartResult.Ready ->
                            ComposeUiState.Editing(
                                seed = start.draft,
                                form = restored?.form ?: start.draft.toForm(),
                                stored = start.draft.toForm(),
                                draftId = (target as? ComposeIntent.Draft)?.emailId,
                            )
                        is StartResult.DraftGone -> ComposeUiState.Failed(uiText(Res.string.compose_draft_gone))
                        is StartResult.OriginalGone -> ComposeUiState.Failed(uiText(Res.string.compose_original_gone))
                        is SendUnavailable -> ComposeUiState.Failed(start.message())
                    }
            } catch (e: JmapException) {
                _state.value = ComposeUiState.Failed(e.userMessage())
            }
            if (restored != null) scheduleSave()
        }
    }

    fun edit(change: ComposeForm.() -> ComposeForm) {
        val current = _state.value as? ComposeUiState.Editing ?: return
        _state.value = current.copy(form = current.form.change())
        scheduleSave()
    }

    /**
     * Whether the screen may close now. A form that is saved, or still saving, is kept as a server
     * draft. Only text the server refused asks first, and a second back means discard.
     */
    fun requestClose(): Boolean {
        val current = _state.value as? ComposeUiState.Editing ?: return true
        if (current.confirmingDiscard) {
            discard()
            return true
        }
        if (!current.saveFailed) return true
        _state.value = current.copy(confirmingDiscard = true)
        return false
    }

    fun keepEditing() {
        val current = _state.value as? ComposeUiState.Editing ?: return
        _state.value = current.copy(confirmingDiscard = false)
    }

    /** Saves what the form holds now, for a screen that is going away. */
    fun release() {
        pendingSave?.cancel()
        save()
    }

    fun retrySave() = save()

    fun discard() {
        discarded = true
        pendingSave?.cancel()
        viewModelScope.launch {
            saves.withLock {
                val draftId = (_state.value as? ComposeUiState.Editing)?.draftId ?: return@withLock
                try {
                    composeRepository.discard(draftId)
                } catch (_: JmapException) {
                    // The draft stays in Drafts, where it can be opened and discarded again.
                }
            }
        }
    }

    fun send() {
        val current = _state.value as? ComposeUiState.Editing ?: return
        if (current.sending) return
        val draft = current.form.applyTo(current.seed)
        if (draft.to.isEmpty()) {
            _state.value = current.copy(error = uiText(Res.string.compose_add_recipient))
            return
        }
        pendingSave?.cancel()
        _state.value = current.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                persist(sending = true)?.let { return@launch fail(it) }
                val draftId = checkNotNull((_state.value as ComposeUiState.Editing).draftId)
                when (val result = composeRepository.send(draftId)) {
                    is SendResult.Sent -> _state.value = ComposeUiState.Sent
                    is SendResult.Rejected -> fail(UiText.FromServer(result.reason))
                    is SendUnavailable -> fail(result.message())
                }
            } catch (e: JmapException) {
                fail(e.userMessage())
            }
        }
    }

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave =
            viewModelScope.launch {
                delay(AUTOSAVE_DELAY)
                save()
            }
    }

    private fun save() {
        viewModelScope.launch { persist() }
    }

    /** Brings the server draft in line with the form; returns why it could not, or null. */
    private suspend fun persist(sending: Boolean = false): UiText? =
        saves.withLock {
            val current = _state.value as? ComposeUiState.Editing ?: return null
            val unsent = sending && current.draftId == null
            if (discarded || (current.form == current.stored && !unsent)) return null
            val form = current.form
            val failure =
                try {
                    store(current, form)
                } catch (e: JmapException) {
                    e.userMessage()
                }
            _state.update { if (it is ComposeUiState.Editing) it.copy(saveFailed = failure != null) else it }
            failure
        }

    private suspend fun store(
        current: ComposeUiState.Editing,
        form: ComposeForm,
    ): UiText? {
        if (form.isBlank()) {
            current.draftId?.let { composeRepository.discard(it) }
            _state.update { if (it is ComposeUiState.Editing) it.copy(draftId = null, stored = form) else it }
            return null
        }
        return when (val saved = composeRepository.save(form.applyTo(current.seed), current.draftId)) {
            is DraftSaveResult.Saved -> {
                _state.update { if (it is ComposeUiState.Editing) it.copy(draftId = saved.emailId, stored = form) else it }
                null
            }
            is DraftSaveResult.Rejected -> UiText.FromServer(saved.reason)
            is SendUnavailable -> saved.message()
        }
    }

    private fun fail(reason: UiText) {
        val current = _state.value as ComposeUiState.Editing
        _state.value = current.copy(sending = false, error = uiText(Res.string.compose_not_sent, reason))
    }
}

private val AUTOSAVE_DELAY = 2.seconds

private fun ComposeForm.isBlank() = listOf(to, cc, bcc, subject, body).all { it.isBlank() }

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
