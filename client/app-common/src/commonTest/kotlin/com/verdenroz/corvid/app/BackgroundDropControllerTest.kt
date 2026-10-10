package com.verdenroz.corvid.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundDropControllerTest {
    @Test
    fun `thirty seconds in the background drops the key`() =
        runTest {
            var timeoutCount = 0
            val controller = BackgroundDropController(backgroundScope, onTimeout = { timeoutCount++ })

            controller.enteredBackground()
            advanceTimeBy(31.seconds)

            assertEquals(1, timeoutCount)
        }

    @Test
    fun `returning to the foreground in time cancels the drop`() =
        runTest {
            var timeoutCount = 0
            val controller = BackgroundDropController(backgroundScope, onTimeout = { timeoutCount++ })

            controller.enteredBackground()
            advanceTimeBy(10.seconds)
            controller.enteredForeground()
            advanceTimeBy(30.seconds)

            assertEquals(0, timeoutCount)
        }

    @Test
    fun `every collector sees a return to the foreground`() =
        runTest {
            val controller = BackgroundDropController(backgroundScope, onTimeout = {})
            val first = async(start = CoroutineStart.UNDISPATCHED) { controller.foregrounded.first() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { controller.foregrounded.first() }

            controller.enteredForeground()

            assertEquals(Unit, first.await())
            assertEquals(Unit, second.await())
        }
}
