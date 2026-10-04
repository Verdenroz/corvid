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
 * Drops the key after [timeout] idle. [recordActivity] restarts the timer
 * from zero; the root composable calls it on every pointer event it sees.
 * [locksAt] is when the key drops if nothing else happens, null while stopped.
 */
class IdleTimeoutController(
    private val scope: CoroutineScope,
    private val onIdle: () -> Unit,
    timeout: Duration = IDLE_TIMEOUT,
    private val clock: Clock = Clock.System,
) {
    var timeout: Duration = timeout
        private set
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
        _locksAt.value = now + timeout
        job =
            scope.launch {
                delay(timeout)
                _locksAt.value = null
                onIdle()
            }
    }

    /** A running timer restarts under the new value; a stopped one just keeps it for the next start. */
    fun setTimeout(value: Duration) {
        if (value == timeout) return
        timeout = value
        if (job?.isActive == true) {
            lastActivity = null
            recordActivity()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _locksAt.value = null
    }
}
