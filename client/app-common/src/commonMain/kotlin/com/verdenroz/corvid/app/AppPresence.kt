package com.verdenroz.corvid.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the user can see the app at all, as the platform reports it (an Android activity started, a desktop window
 * focused and not minimised), and the requests to show the Inbox that a tapped new-mail notification makes.
 */
class AppPresence {
    private val _inView = MutableStateFlow(true)
    val inView: StateFlow<Boolean> = _inView

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Delivered only to an unlocked session already collecting; a locked app opens on the Inbox after unlocking anyway. */
    val inboxRequests: Flow<Unit> = requests

    private val digests = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The ids of the digests a tapped notification asks to show, delivered like [inboxRequests]. */
    val digestRequests: Flow<String> = digests

    private val arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each digest push that reached this device, for a session that is collecting; one with nobody collecting is dropped. */
    val digestArrivals: Flow<Unit> = arrivals

    fun shown(inView: Boolean) {
        _inView.value = inView
    }

    fun requestInbox() {
        requests.tryEmit(Unit)
    }

    fun digestArrived() {
        arrivals.tryEmit(Unit)
    }

    fun requestDigest(id: String) {
        digests.tryEmit(id)
    }
}

/** Asks the platform for permission to post notifications, where it needs one. */
interface NotificationPermission {
    /** Asks unless this run already has, for a moment the user did not choose, such as unlocking. */
    fun askOnce()

    /** Asks now, for when the user has just turned notifications on. */
    fun ask()
}
