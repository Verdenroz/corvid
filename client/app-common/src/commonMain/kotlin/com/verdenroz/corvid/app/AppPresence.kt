package com.verdenroz.corvid.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Where a tapped notification asks the app to go. */
sealed interface OpenRequest {
    data object Inbox : OpenRequest

    data class Digest(val id: String) : OpenRequest
}

/**
 * Whether the user can see the app at all, as the platform reports it (an Android activity started, a desktop window
 * focused and not minimised), the open request a tapped notification makes, and the digest pushes that arrive.
 */
class AppPresence {
    private val _inView = MutableStateFlow(true)
    val inView: StateFlow<Boolean> = _inView

    private val _pending = MutableStateFlow<OpenRequest?>(null)

    /** The latest open request nobody has taken; a locked app keeps it until a session unlocks and takes it. */
    val pending: StateFlow<OpenRequest?> = _pending

    private val arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each digest push that reached this device, for a session that is collecting; one with nobody collecting is dropped. */
    val digestArrivals: Flow<Unit> = arrivals

    fun shown(inView: Boolean) {
        _inView.value = inView
    }

    fun requestInbox() {
        _pending.value = OpenRequest.Inbox
    }

    fun requestDigest(id: String) {
        _pending.value = OpenRequest.Digest(id)
    }

    /** The pending request, which is then no longer pending. */
    fun take(): OpenRequest? = _pending.getAndUpdate { null }

    fun digestArrived() {
        arrivals.tryEmit(Unit)
    }
}

/** Asks the platform for permission to post notifications, where it needs one. */
interface NotificationPermission {
    /** Asks unless this run already has, for a moment the user did not choose, such as unlocking. */
    fun askOnce()

    /** Asks now, for when the user has just turned notifications on. */
    fun ask()
}
