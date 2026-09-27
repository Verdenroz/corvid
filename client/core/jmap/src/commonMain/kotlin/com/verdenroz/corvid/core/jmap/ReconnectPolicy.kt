package com.verdenroz.corvid.core.jmap

import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with full jitter, capped at 5 minutes. Runs in the
 * foreground only: a backgrounded app drops its key instead of retrying.
 * A manual Retry goes through [nextDelay] like any other attempt, so it
 * shares the same cap and the same [AuthLatch].
 */
class ReconnectPolicy(private val random: Random = Random.Default) {
    private var attempt = 0

    fun nextDelay(): Duration {
        val exponential = BASE.inWholeMilliseconds * (1L shl min(attempt, MAX_SHIFT))
        val capped = min(exponential, CAP.inWholeMilliseconds)
        attempt++
        return random.nextLong(0, capped + 1).milliseconds
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        val BASE = 1.seconds
        val CAP = 5.minutes
        const val MAX_SHIFT = 20
    }
}
