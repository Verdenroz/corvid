package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.JmapException
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
                val email = repository.getEmail(emailId)
                _state.value =
                    ReaderUiState.Loaded(
                        subject = email.subject,
                        from = email.from,
                        to = email.to,
                        cc = email.cc,
                        body = email.body,
                        bodyTruncated = email.bodyTruncated,
                    )
            } catch (e: JmapException) {
                _state.value = ReaderUiState.Failed("could not reach the server")
            }
        }
    }
}
