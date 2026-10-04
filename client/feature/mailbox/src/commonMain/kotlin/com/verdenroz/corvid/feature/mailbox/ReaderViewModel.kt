package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.Correction
import com.verdenroz.corvid.core.data.EmailLookup
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.keywordPatch
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.teach_refused
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val repository: MailboxRepository,
    private val emailId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state

    init {
        viewModelScope.launch {
            try {
                load()
            } catch (e: JmapException) {
                _state.value = ReaderUiState.Failed(e.userMessage())
            }
        }
    }

    /** Writes the answer, then rereads the keywords, so the labels shown are the server's, not a guess at them. */
    fun answer(correction: Correction) {
        val current = _state.value as? ReaderUiState.Loaded ?: return
        if (current.answering) return
        _state.value = current.copy(answering = true, answerError = null)
        viewModelScope.launch {
            try {
                val refused = repository.updateKeywords(emailId, correction.keywordPatch())
                if (refused == null) showKeywords() else fail(uiText(Res.string.teach_refused, UiText.FromServer(refused)))
            } catch (e: JmapException) {
                fail(e.userMessage())
            }
        }
    }

    private suspend fun load() {
        _state.value =
            when (val lookup = repository.getEmail(emailId)) {
                is EmailLookup.NotFound -> ReaderUiState.Gone
                is EmailLookup.Found -> ReaderUiState.Loaded(lookup.detail)
            }
    }

    private suspend fun showKeywords() {
        val keywords = repository.keywords(emailId)
        _state.update { current ->
            when {
                current !is ReaderUiState.Loaded -> current
                keywords == null -> ReaderUiState.Gone
                else -> current.copy(email = current.email.copy(keywords = keywords), answering = false)
            }
        }
    }

    private fun fail(reason: UiText) {
        val current = _state.value as? ReaderUiState.Loaded ?: return
        _state.value = current.copy(answering = false, answerError = reason)
    }
}
