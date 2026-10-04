package com.verdenroz.corvid.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val IDLE_TIMEOUT = 5.minutes

/**
 * Drops the key after 5 idle minutes. [recordActivity] restarts the timer
 * from zero; the root composable calls it on every pointer event it sees.
 */
class IdleTimeoutController(
    private val scope: CoroutineScope,
    private val onIdle: () -> Unit,
    private val timeout: Duration = IDLE_TIMEOUT,
) {
    private var job: Job? = null

    fun recordActivity() {
        job?.cancel()
        job =
            scope.launch {
                delay(timeout)
                onIdle()
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
