package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.Directory
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DirectoryUiState {
    data object Loading : DirectoryUiState

    data class Loaded(val directory: Directory) : DirectoryUiState

    data class Failed(val message: UiText) : DirectoryUiState
}

/**
 * People, Sources and Labels, all read from one fetch of the newest messages. Nothing loads until one of them is shown;
 * after that, a mail change refetches while one is on screen, or when one is next shown.
 */
class DirectoryViewModel(private val repository: MailboxRepository) : ViewModel() {
    private val _state = MutableStateFlow<DirectoryUiState>(DirectoryUiState.Loading)
    val state: StateFlow<DirectoryUiState> = _state

    private var loading: Job? = null
    private var onScreen = false
    private var stale = true

    fun mailChanged() {
        stale = true
        if (onScreen) load()
    }

    fun shown(onScreen: Boolean) {
        this.onScreen = onScreen
        if (onScreen && stale) load()
    }

    private fun load() {
        stale = false
        loading?.cancel()
        loading =
            viewModelScope.launch {
                _state.value =
                    try {
                        DirectoryUiState.Loaded(repository.directory())
                    } catch (e: JmapException) {
                        stale = true
                        DirectoryUiState.Failed(e.userMessage())
                    }
            }
    }
}
