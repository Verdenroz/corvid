package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [inboxUnread] is null until the server has answered; a failed refresh keeps the last answer. */
data class ShellState(
    val inboxUnread: Int? = null,
    val identity: Identity? = null,
)

class ShellViewModel(private val repository: MailboxRepository) : ViewModel() {
    private val _state = MutableStateFlow(ShellState())
    val state: StateFlow<ShellState> = _state

    fun refresh() {
        viewModelScope.launch {
            try {
                val unread = repository.findMailboxByRole("inbox")?.unreadEmails
                val identity = repository.identity()
                _state.update { it.copy(inboxUnread = unread, identity = identity) }
            } catch (e: JmapException) {
                return@launch
            }
        }
    }
}
