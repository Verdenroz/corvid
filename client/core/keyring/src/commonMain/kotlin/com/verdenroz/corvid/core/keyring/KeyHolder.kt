package com.verdenroz.corvid.core.keyring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface KeyState {
    data object Locked : KeyState

    class Unlocked(val key: ByteArray) : KeyState
}

/**
 * Holds the device key in memory only, for as long as the app is unlocked.
 * [drop] overwrites the array before releasing it; on the JVM this is
 * best-effort only, since nothing prevents the JIT or GC from having
 * already copied the bytes elsewhere before the overwrite runs.
 */
class KeyHolder {
    private val _state = MutableStateFlow<KeyState>(KeyState.Locked)
    val state: StateFlow<KeyState> = _state

    fun unlock(key: ByteArray) {
        _state.value = KeyState.Unlocked(key)
    }

    fun drop() {
        (_state.value as? KeyState.Unlocked)?.key?.fill(0)
        _state.value = KeyState.Locked
    }
}
