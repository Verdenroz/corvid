package com.verdenroz.corvid.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether the device has a network to reach the server over; the app shell binds the platform's. */
interface Connectivity {
    val online: StateFlow<Boolean>

    /** False when [online] is only known by polling, so nothing should hold it just to hear of a change. */
    val eventDriven: Boolean
}

/**
 * For a platform with no network callback: [probe] runs while something collects [online], every
 * [onlineEvery] while it succeeds and at a doubling interval from 5 to 60 seconds while it fails.
 */
class PollingConnectivity(
    scope: CoroutineScope,
    onlineEvery: Duration = 15.seconds,
    probe: suspend () -> Boolean,
) : Connectivity {
    override val eventDriven = false

    override val online: StateFlow<Boolean> =
        flow {
            var failures = 0
            while (true) {
                val up = probe()
                emit(up)
                failures = if (up) 0 else failures + 1
                delay(if (up) onlineEvery else offlineWait(failures))
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(), true)

    private fun offlineWait(failures: Int) = minOf(OFFLINE_FIRST * (1 shl min(failures - 1, MAX_SHIFT)), OFFLINE_LAST)

    private companion object {
        val OFFLINE_FIRST = 5.seconds
        val OFFLINE_LAST = 60.seconds
        const val MAX_SHIFT = 4
    }
}
