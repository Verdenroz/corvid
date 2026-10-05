package com.verdenroz.corvid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Notice
import com.verdenroz.corvid.core.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface NoticesState {
    data object Loading : NoticesState

    /** The server answers 404: it is older than the notices feed. */
    data object Unsupported : NoticesState

    data class Failed(val message: UiText) : NoticesState

    /** Newest first. */
    data class Ready(val notices: List<Notice>) : NoticesState
}

class NoticesViewModel(private val repository: NoticeRepository) : ViewModel() {
    private val _state = MutableStateFlow<NoticesState>(NoticesState.Loading)
    val state: StateFlow<NoticesState> = _state

    init {
        viewModelScope.launch {
            _state.value =
                try {
                    repository.after()?.let { NoticesState.Ready(it.asReversed()) } ?: NoticesState.Unsupported
                } catch (e: JmapException) {
                    NoticesState.Failed(e.userMessage())
                }
        }
    }
}
