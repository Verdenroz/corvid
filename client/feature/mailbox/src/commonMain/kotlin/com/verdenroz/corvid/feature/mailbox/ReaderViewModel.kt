package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.EmailLookup
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                when (val lookup = repository.getEmail(emailId)) {
                    is EmailLookup.NotFound -> _state.value = ReaderUiState.Gone
                    is EmailLookup.Found -> _state.value = ReaderUiState.Loaded(lookup.detail)
                }
            } catch (e: JmapException) {
                _state.value = ReaderUiState.Failed(e.userMessage())
            }
        }
    }
}
