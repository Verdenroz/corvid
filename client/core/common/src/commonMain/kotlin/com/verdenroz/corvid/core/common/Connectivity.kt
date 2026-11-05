package com.verdenroz.corvid.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration

/** Whether the device has a network to reach the server over; the app shell binds the platform's. */
interface Connectivity {
    val online: StateFlow<Boolean>
}

/** For a platform with no network callback: [probe] runs every [interval] while something collects [online]. */
class PollingConnectivity(
    scope: CoroutineScope,
    interval: Duration,
    probe: suspend () -> Boolean,
) : Connectivity {
    override val online: StateFlow<Boolean> =
        flow {
            while (true) {
                emit(probe())
                delay(interval)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(), true)
}
