package com.verdenroz.corvid.feature.digest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Digest
import com.verdenroz.corvid.core.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DigestState {
    data object Loading : DigestState

    /** No digest was made yet, or the server does not make them. */
    data object Empty : DigestState

    data class Failed(val message: UiText) : DigestState

    data class Ready(val digest: Digest) : DigestState {
        val anySummary: Boolean get() = digest.groups.any { group -> group.items.any { it.summary != null } }
    }
}

/** The digest [id] names, or the latest when it is null. */
class DigestViewModel(
    private val repository: DigestRepository,
    private val id: String?,
) : ViewModel() {
    private val _state = MutableStateFlow<DigestState>(DigestState.Loading)
    val state: StateFlow<DigestState> = _state

    init {
        viewModelScope.launch {
            _state.value =
                try {
                    val digest = if (id == null) repository.latest() else repository.get(id)
                    digest?.let { DigestState.Ready(it) } ?: DigestState.Empty
                } catch (e: JmapException) {
                    DigestState.Failed(e.userMessage())
                }
        }
    }
}
