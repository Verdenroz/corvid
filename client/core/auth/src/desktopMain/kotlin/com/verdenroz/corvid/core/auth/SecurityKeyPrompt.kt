package com.verdenroz.corvid.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface KeyPromptState {
    data object Idle : KeyPromptState

    /** The key wants its PIN; [wrong] says the last one was refused. */
    data class Pin(val wrong: Boolean) : KeyPromptState

    /** The key is waiting to be touched. */
    data object Touch : KeyPromptState
}

/**
 * What the person sees while a security key works: a PIN field, then a request to touch the key.
 * The window shows [state]; [submitPin] and [cancel] are its buttons.
 */
class SecurityKeyPrompt {
    private val _state = MutableStateFlow<KeyPromptState>(KeyPromptState.Idle)
    val state: StateFlow<KeyPromptState> = _state

    private var pin: CompletableDeferred<String?>? = null
    private val cancels = MutableStateFlow(0)

    fun submitPin(pin: String) {
        this.pin?.complete(pin)
    }

    fun cancel() {
        pin?.complete(null)
        cancels.update { it + 1 }
    }

    /** The PIN the person types, or null if they cancel. */
    internal suspend fun askPin(wrong: Boolean): String? {
        val answer = CompletableDeferred<String?>()
        pin = answer
        _state.value = KeyPromptState.Pin(wrong)
        try {
            return answer.await()
        } finally {
            pin = null
            _state.value = KeyPromptState.Idle
        }
    }

    /** Shows the touch request while [block] runs, and calls [onCancel] if the person cancels meanwhile. */
    internal suspend fun <T> touching(
        onCancel: () -> Unit,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            val before = cancels.value
            _state.value = KeyPromptState.Touch
            val watcher = launch { cancels.first { it != before }.also { onCancel() } }
            try {
                block()
            } finally {
                watcher.cancel()
                _state.value = KeyPromptState.Idle
            }
        }
}
