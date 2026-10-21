package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What is left until [deadline], ticking once a second and stopping at zero; null while no deadline is set. */
@Composable
fun rememberRemaining(deadline: Instant?): Duration? {
    val remaining by produceState(deadline?.let(::left), deadline) {
        while (deadline != null) {
            value = left(deadline)
            if (value == Duration.ZERO) return@produceState
            delay(1.seconds)
        }
        value = null
    }
    return remaining
}

private fun left(deadline: Instant): Duration = (deadline - Clock.System.now()).coerceAtLeast(Duration.ZERO)
