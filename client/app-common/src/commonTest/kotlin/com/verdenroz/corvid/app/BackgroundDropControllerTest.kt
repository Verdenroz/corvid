package com.verdenroz.corvid.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `a return to the foreground nobody was reading yet is still delivered`() =
        runTest {
            val controller = BackgroundDropController(backgroundScope, onTimeout = {})

            controller.enteredForeground()
            controller.enteredForeground()

            assertEquals(Unit, controller.foregrounded.first())
        }
}
