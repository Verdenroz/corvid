package com.verdenroz.corvid.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PollingConnectivityTest {
    @Test
    fun `it does not probe while nothing collects`() =
        runTest {
            var probes = 0
            PollingConnectivity(backgroundScope, onlineEvery = 10.seconds) { true.also { probes++ } }
            advanceTimeBy(60.seconds)
            assertEquals(0, probes)
        }

    @Test
    fun `it reports each change the probe sees`() =
        runTest {
            var reachable = true
            val connectivity = PollingConnectivity(backgroundScope, onlineEvery = 10.seconds) { reachable }
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { connectivity.online.collect { seen += it } }
            runCurrent()
            reachable = false
            advanceTimeBy(11.seconds)
            reachable = true
            advanceTimeBy(10.seconds)
            assertEquals(listOf(true, false, true), seen)
        }

    @Test
    fun `it stops probing once the last collector leaves`() =
        runTest {
            var probes = 0
            val connectivity = PollingConnectivity(backgroundScope, onlineEvery = 10.seconds) { true.also { probes++ } }
            val collector = backgroundScope.launch { connectivity.online.collect { } }
            advanceTimeBy(25.seconds)
            collector.cancel()
            runCurrent()
            val before = probes
            advanceTimeBy(60.seconds)
            assertEquals(before, probes)
        }

    @Test
    fun `it backs off while offline, from five seconds up to a minute`() =
        runTest {
            val times = mutableListOf<Long>()
            val connectivity = PollingConnectivity(backgroundScope) { false.also { times += testScheduler.currentTime } }
            backgroundScope.launch { connectivity.online.collect { } }
            advanceTimeBy(5.minutes)

            val gaps = times.zipWithNext { a, b -> (b - a) / 1000 }
            assertEquals(listOf(5L, 10L, 20L, 40L, 60L, 60L), gaps.take(6))
        }

    @Test
    fun `it goes back to the online interval once the probe succeeds`() =
        runTest {
            val times = mutableListOf<Long>()
            var reachable = false
            val connectivity =
                PollingConnectivity(backgroundScope, onlineEvery = 15.seconds) { reachable.also { times += testScheduler.currentTime } }
            backgroundScope.launch { connectivity.online.collect { } }
            advanceTimeBy(6.seconds)
            reachable = true
            advanceTimeBy(60.seconds)

            val gaps = times.zipWithNext { a, b -> (b - a) / 1000 }
            assertEquals(listOf(5L, 10L, 15L), gaps.take(3))
        }

    @Test
    fun `it is not event driven`() {
        runTest { assertFalse(PollingConnectivity(backgroundScope) { true }.eventDriven) }
    }
}
