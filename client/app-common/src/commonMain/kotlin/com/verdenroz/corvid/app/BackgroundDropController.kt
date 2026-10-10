package com.verdenroz.corvid.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val returned = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each return to the foreground, delivered to every collector active at the time; one with nobody collecting is dropped. */
    val foregrounded: Flow<Unit> = returned

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
        returned.tryEmit(Unit)
    }
}
