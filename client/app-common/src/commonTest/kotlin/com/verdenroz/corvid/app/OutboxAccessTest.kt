package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.outbox.OutboxAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
}
