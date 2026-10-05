package com.verdenroz.corvid.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private val BACKGROUND_TIMEOUT = 30.seconds

/**
 * Drops the key when the app is backgrounded or minimised: the platform calls
 * [enteredBackground] and, unless [enteredForeground] cancels it first, the
 * key drops 30 seconds later.
 */
class BackgroundDropController(
    private val scope: CoroutineScope,
    private val onTimeout: () -> Unit,
) {
    private var job: Job? = null
    private val returned = Channel<Unit>(Channel.CONFLATED)

    /** A return to the foreground, held until read and merged with any not yet read; one reader at a time. */
    val foregrounded: Flow<Unit> = returned.receiveAsFlow()

    fun enteredBackground() {
        job =
            scope.launch {
                delay(BACKGROUND_TIMEOUT)
                onTimeout()
            }
    }

    fun enteredForeground() {
        job?.cancel()
        job = null
        returned.trySend(Unit)
    }
}
