package com.verdenroz.corvid.feature.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.Contact
import com.verdenroz.corvid.core.data.DRAFTS
import com.verdenroz.corvid.core.data.DraftSaveResult
import com.verdenroz.corvid.core.data.PickedFile
import com.verdenroz.corvid.core.data.SendResult
import com.verdenroz.corvid.core.data.SendUnavailable
import com.verdenroz.corvid.core.data.StartResult
import com.verdenroz.corvid.core.data.UploadResult
import com.verdenroz.corvid.core.data.fileSize
import com.verdenroz.corvid.core.data.matching
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Attachment
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
import com.verdenroz.corvid.feature.compose.resources.compose_not_uploaded
import com.verdenroz.corvid.feature.compose.resources.compose_original_gone
import com.verdenroz.corvid.feature.compose.resources.compose_too_large
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
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
    private var contacts: List<Contact> = emptyList()
    private var typing: Typing? = null

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
                        is StartResult.DraftGone -> restoredAsNew() ?: ComposeUiState.Failed(uiText(Res.string.compose_draft_gone))
                        is StartResult.OriginalGone -> ComposeUiState.Failed(uiText(Res.string.compose_original_gone))
                        is SendUnavailable -> ComposeUiState.Failed(start.message())
                    }
            } catch (e: JmapException) {
                _state.value = ComposeUiState.Failed(e.userMessage())
            }
            if (restored != null) scheduleSave()
        }
        viewModelScope.launch {
            contacts =
                try {
                    composeRepository.contacts()
                } catch (_: JmapException) {
                    // Suggestions are a convenience; a field typed in full still sends.
                    emptyList()
                }
            updateEditing { suggesting() }
        }
    }

    /** The restored text outlives a draft that was replaced or sent in the meantime; it saves as a new one. */
    private suspend fun restoredAsNew(): ComposeUiState.Editing? {
        val form = restored?.form?.takeUnless { it.isBlank() } ?: return null
        val seed = (composeRepository.start(intent) as? StartResult.Ready)?.draft ?: Draft()
        return ComposeUiState.Editing(seed = seed, form = form, stored = seed.toForm())
    }

    fun edit(change: ComposeForm.() -> ComposeForm) {
        val current = _state.value as? ComposeUiState.Editing ?: return
        _state.value = current.copy(form = current.form.change())
        scheduleSave()
    }

    /** [caret] is where the cursor sits in [text], so the address around it is the one suggestions complete. */
    fun editRecipients(
        field: RecipientField,
        text: String,
        caret: Int = text.length,
    ) {
        val current = _state.value as? ComposeUiState.Editing ?: return
        typing = Typing(field, caret.coerceIn(0, text.length))
        _state.value = current.copy(form = current.form.withRecipients(field, text)).suggesting()
        scheduleSave()
    }

    /** Moves the highlight by [step], stopping at either end of the list. */
    fun moveHighlight(step: Int) {
        val current = _state.value as? ComposeUiState.Editing ?: return
        val suggestions = current.suggestions ?: return
        val highlighted = (suggestions.highlighted + step).coerceIn(0, suggestions.contacts.lastIndex)
        _state.value = current.copy(suggestions = suggestions.copy(highlighted = highlighted))
    }

    /** Puts the suggestion at [index], the highlighted one by default, in place of the address being typed. */
    fun acceptSuggestion(index: Int? = null) {
        val current = _state.value as? ComposeUiState.Editing ?: return
        val suggestions = current.suggestions ?: return
        val contact = suggestions.contacts.getOrNull(index ?: suggestions.highlighted) ?: return
        val field = suggestions.field
        val text = current.form.recipients(field)
        val replaced = AddressSpan(text, typing?.caret ?: text.length).replacedBy(contact.address)
        typing = null
        _state.value = current.copy(form = current.form.withRecipients(field, replaced), suggestions = null)
        scheduleSave()
    }

    /** Closes the list until the next keystroke; true when there was one to close. */
    fun dismissSuggestions(): Boolean {
        val current = _state.value as? ComposeUiState.Editing ?: return false
        typing = null
        if (current.suggestions == null) return false
        _state.value = current.copy(suggestions = null)
        return true
    }

    private fun ComposeUiState.Editing.suggesting(): ComposeUiState.Editing {
        val typing = typing ?: return copy(suggestions = null)
        val span = AddressSpan(form.recipients(typing.field), typing.caret)
        val fragment = span.fragment
        val taken = (span.others.map { it.email } + fragment).map { it.lowercase() }.toSet()
        val found = contacts.filterNot { it.address.email.lowercase() in taken }.matching(fragment, SUGGESTION_LIMIT)
        return copy(suggestions = found.takeIf { it.isNotEmpty() }?.let { Suggestions(typing.field, it) })
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

    /** Uploads each file and adds it to the form once the server holds it; the draft saves after each one lands. */
    fun attach(files: List<PickedFile>) {
        files.forEach { file ->
            val current = _state.value as? ComposeUiState.Editing ?: return
            _state.value = current.copy(uploading = current.uploading + file.name, attachError = null)
            viewModelScope.launch {
                val failure = upload(file)
                updateEditing { copy(uploading = uploading - file.name, attachError = failure ?: attachError) }
                if (failure == null) scheduleSave()
            }
        }
    }

    fun detach(attachment: Attachment) = edit { copy(attachments = attachments - attachment) }

    private suspend fun upload(file: PickedFile): UiText? =
        try {
            when (val result = composeRepository.upload(file)) {
                is UploadResult.Uploaded -> {
                    updateEditing { copy(form = form.copy(attachments = form.attachments + result.attachment)) }
                    null
                }
                is UploadResult.TooLarge -> uiText(Res.string.compose_too_large, file.name, fileSize(result.limit))
            }
        } catch (e: JmapException) {
            uiText(Res.string.compose_not_uploaded, file.name, e.userMessage())
        } catch (e: IOException) {
            uiText(Res.string.compose_not_uploaded, file.name, UiText.FromServer(e.message.orEmpty()))
        }

    private fun updateEditing(change: ComposeUiState.Editing.() -> ComposeUiState.Editing) {
        _state.update { if (it is ComposeUiState.Editing) it.change() else it }
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
        if (current.sending || current.uploading.isNotEmpty()) return
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
            updateEditing { copy(saveFailed = failure != null) }
            failure
        }

    private suspend fun store(
        current: ComposeUiState.Editing,
        form: ComposeForm,
    ): UiText? {
        if (form.isBlank()) {
            current.draftId?.let { composeRepository.discard(it) }
            updateEditing { copy(draftId = null, stored = form) }
            return null
        }
        return when (val saved = composeRepository.save(form.applyTo(current.seed), current.draftId)) {
            is DraftSaveResult.Saved -> {
                updateEditing { copy(draftId = saved.emailId, stored = form) }
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
private const val SUGGESTION_LIMIT = 6

private class Typing(val field: RecipientField, val caret: Int)

private fun ComposeForm.isBlank() = listOf(to, cc, bcc, subject, body).all { it.isBlank() } && attachments.isEmpty()

private fun SendUnavailable.message() =
    when (this) {
        is SendUnavailable.NoIdentity -> uiText(Res.string.compose_no_identity)
        is SendUnavailable.NoMailbox ->
            uiText(
                if (role == DRAFTS) Res.string.compose_no_drafts_mailbox else Res.string.compose_no_sent_mailbox,
            )
    }

private fun Draft.toForm() =
    ComposeForm(
        to = to.joinToString(", ") { it.toText() },
        cc = cc.joinToString(", ") { it.toText() },
        bcc = bcc.joinToString(", ") { it.toText() },
        subject = subject,
        body = body,
        attachments = attachments,
    )

private fun ComposeForm.applyTo(seed: Draft) =
    seed.copy(
        to = parseAddresses(to),
        cc = parseAddresses(cc),
        bcc = parseAddresses(bcc),
        subject = subject,
        body = body,
        attachments = attachments,
    )
