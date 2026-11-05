package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }
private val DRAFT = Draft(to = listOf(EmailAddress(null, "bob@example.test")), subject = "hello", body = "hi")

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.outbox(store: OutboxStore): Outbox {
    val client = FakeJmapServer().withSendableAccount().client()
    val sender = OutboxSender(store, client, ComposeRepository(client, MailboxRepository(client)), DEVICE_KEY)
    val queue = OutboxQueue(store, OutboxSealer.keyFor(DEVICE_KEY), "example.test")
    return Outbox(store, queue, sender, DEVICE_KEY, CoroutineScope(StandardTestDispatcher(testScheduler)))
}

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {
    private val store = assertNotNull(OutboxStore.open(createTempDirectory().toFile()))

    @Test
    fun `queueing writes nothing until the outbox's own scope runs`() =
        runTest {
            val outbox = outbox(store)
            runCurrent()

            val id = async { outbox.enqueue(DRAFT, null) }
            assertEquals(emptyList(), store.list().items)
            runCurrent()

            assertEquals(listOf(id.await()), store.list().items.map { it.id })
        }

    @Test
    fun `a queued message reads back in the entries with its draft`() =
        runTest {
            val outbox = outbox(store)

            val id = async { outbox.enqueue(DRAFT, null) }
            runCurrent()

            val entry = outbox.entries.value.single()
            assertEquals(id.await(), entry.item.id)
            assertEquals(Contents.Readable(DRAFT), entry.contents)
            assertIs<OutboxState.Queued>(entry.item.state)
        }

    @Test
    fun `entries are empty until the first read has run`() =
        runTest {
            store.enqueue(
                "a",
                OutboxSealer.seal("a", OutboxMessage(DRAFT, null, "a@example.test", "k", 1), OutboxSealer.keyFor(DEVICE_KEY)),
                1,
            )
            val outbox = outbox(store)

            assertEquals(emptyList(), outbox.entries.value)
            runCurrent()

            assertEquals(listOf("a"), outbox.entries.value.map { it.item.id })
        }
}
