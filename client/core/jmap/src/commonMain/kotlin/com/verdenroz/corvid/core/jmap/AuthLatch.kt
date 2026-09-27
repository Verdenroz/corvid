package com.verdenroz.corvid.core.jmap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Trips on the first 401 from any endpoint. While tripped, [JmapClient]
 * refuses every call without touching the network; only [clear], called
 * after an interactive sign-in, lets calls through again.
 */
class AuthLatch {
    private val _tripped = MutableStateFlow(false)
    val tripped: StateFlow<Boolean> = _tripped

    fun trip() {
        _tripped.value = true
    }

    fun clear() {
        _tripped.value = false
    }
}
