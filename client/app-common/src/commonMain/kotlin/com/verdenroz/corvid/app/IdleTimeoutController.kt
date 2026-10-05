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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val IDLE_TIMEOUT = 5.minutes

/**
 * Drops the key after [timeout] idle, read each time the timer restarts. [recordActivity]
 * restarts the timer from zero; the root composable calls it on every pointer event it sees.
 * [locksAt] is when the key drops if nothing else happens, null while stopped.
 */
class IdleTimeoutController(
    private val scope: CoroutineScope,
    private val onIdle: () -> Unit,
    private val timeout: () -> Duration = { IDLE_TIMEOUT },
    private val clock: Clock = Clock.System,
) {
    private var job: Job? = null
    private var lastActivity: Instant? = null
    private val _locksAt = MutableStateFlow<Instant?>(null)
    val locksAt: StateFlow<Instant?> = _locksAt

    /** Activity under a second after the last one is ignored: pointer moves arrive far faster than the deadline needs. */
    fun recordActivity() {
        val now = clock.now()
        if (job?.isActive == true && lastActivity?.let { now - it < 1.seconds } == true) return
        lastActivity = now
        job?.cancel()
        val window = timeout()
        _locksAt.value = now + window
        job =
            scope.launch {
                delay(window)
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
