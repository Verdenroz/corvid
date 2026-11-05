package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.data.outbox.OutboxAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OutboxAccessTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "outbox-io") }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    @Test
    fun `the outbox is opened on the access scope, not on the caller`() =
        runTest {
            var openedOn = ""

            OutboxAccess(scope) { OutboxAvailability.HeldElsewhere.also { openedOn = Thread.currentThread().name } }.use {
                assertEquals(OutboxAvailability.HeldElsewhere, it.await())
            }

            assertTrue(openedOn.startsWith("outbox-io"), openedOn)
            assertNotEquals(openedOn, Thread.currentThread().name)
            executor.shutdown()
        }

    @Test
    fun `a directory that cannot be opened reads as failed rather than throwing`() =
        runTest {
            OutboxAccess(scope) { throw IOException("read-only") }.use {
                assertEquals(OutboxAvailability.Failed, it.await())
            }
            executor.shutdown()
        }

    @Test
    fun `closing the access while the outbox is still opening ends the wait for it`() =
        runTest {
            val access = OutboxAccess(scope) { CompletableDeferred<OutboxAvailability>().await() }
            val waiting = backgroundScope.async { access.await() }
            delay(100)

            access.close()

            assertEquals(
                OutboxAvailability.Failed,
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5.seconds) { waiting.await() }
                },
            )
            executor.shutdown()
        }

    @Test
    fun `resuming on a scope that has closed does nothing`() =
        runTest {
            val closed = koinApplication {}.koin.createScope<UnlockedScopeMarker>("closed").also { it.close() }

            resumeOutbox(closed)
            executor.shutdown()
        }
}
