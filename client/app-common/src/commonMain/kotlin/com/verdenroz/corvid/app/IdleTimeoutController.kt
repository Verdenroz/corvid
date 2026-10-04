package com.verdenroz.corvid.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val IDLE_TIMEOUT = 5.minutes

/**
 * Drops the key after 5 idle minutes. [recordActivity] restarts the timer
 * from zero; the root composable calls it on every pointer event it sees.
 * [locksAt] is when the key drops if nothing else happens, null while stopped.
 */
class IdleTimeoutController(
    private val scope: CoroutineScope,
    private val onIdle: () -> Unit,
    val timeout: Duration = IDLE_TIMEOUT,
    private val clock: Clock = Clock.System,
) {
    private var job: Job? = null
    private val _locksAt = MutableStateFlow<Instant?>(null)
    val locksAt: StateFlow<Instant?> = _locksAt

    fun recordActivity() {
        job?.cancel()
        _locksAt.value = clock.now() + timeout
        job =
            scope.launch {
                delay(timeout)
                _locksAt.value = null
                onIdle()
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        _locksAt.value = null
    }
}
