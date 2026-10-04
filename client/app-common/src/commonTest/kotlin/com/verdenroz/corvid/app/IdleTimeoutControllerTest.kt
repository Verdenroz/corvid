package com.verdenroz.corvid.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class IdleTimeoutControllerTest {
    @Test
    fun `five minutes without activity fires the idle callback once`() =
        runTest {
            var idleCount = 0
            val controller = IdleTimeoutController(backgroundScope, onIdle = { idleCount++ }, timeout = 5.minutes)

            controller.recordActivity()
            advanceTimeBy(5.minutes + 1.seconds)

            assertEquals(1, idleCount)
        }

    @Test
    fun `activity inside the window restarts the timer instead of firing early`() =
        runTest {
            var idleCount = 0
            val controller = IdleTimeoutController(backgroundScope, onIdle = { idleCount++ }, timeout = 5.minutes)

            controller.recordActivity()
            advanceTimeBy(4.minutes)
            controller.recordActivity()
            advanceTimeBy(4.minutes)

            assertEquals(0, idleCount)
        }

    @Test
    fun `stop cancels a pending timeout`() =
        runTest {
            var idleCount = 0
            val controller = IdleTimeoutController(backgroundScope, onIdle = { idleCount++ }, timeout = 5.minutes)

            controller.recordActivity()
            controller.stop()
            advanceTimeBy(6.minutes)

            assertEquals(0, idleCount)
        }

    @Test
    fun `activity moves the lock deadline to a full timeout from now`() =
        runTest {
            var now = Instant.parse("2026-10-04T09:00:00Z")
            val clock =
                object : Clock {
                    override fun now() = now
                }
            val controller = IdleTimeoutController(backgroundScope, onIdle = {}, timeout = 5.minutes, clock = clock)

            controller.recordActivity()
            now += 3.minutes
            controller.recordActivity()

            assertEquals(Instant.parse("2026-10-04T09:08:00Z"), controller.locksAt.value)
        }

    @Test
    fun `there is no deadline once stopped or once the key has dropped`() =
        runTest {
            val controller = IdleTimeoutController(backgroundScope, onIdle = {}, timeout = 5.minutes)

            controller.recordActivity()
            controller.stop()
            assertNull(controller.locksAt.value)

            controller.recordActivity()
            advanceTimeBy(5.minutes + 1.seconds)
            assertNull(controller.locksAt.value)
        }
}
