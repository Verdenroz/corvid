package com.verdenroz.corvid.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val returned = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits each time the app comes back to the foreground. */
    val foregrounded: SharedFlow<Unit> = returned

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
