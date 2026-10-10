package com.verdenroz.corvid.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Runs [poll] on entry, again as soon as [foreground] has emitted or [every] has passed since the last run began;
 * ends when the caller's scope does.
 */
internal suspend fun pollWhileForegrounded(
    foreground: Flow<Unit>,
    every: Duration,
    poll: suspend () -> Unit,
) {
    coroutineScope {
        val due = Channel<Unit>(Channel.CONFLATED)
        launch { foreground.collect { due.trySend(Unit) } }
        launch {
            while (true) {
                delay(every)
                due.trySend(Unit)
            }
        }
        while (true) {
            poll()
            due.receive()
        }
    }
}
