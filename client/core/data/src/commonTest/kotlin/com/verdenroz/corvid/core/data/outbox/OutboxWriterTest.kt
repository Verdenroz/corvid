package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }
private val OTHER_KEY = ByteArray(33) { (it * 5 + 3).toByte() }

private val DRAFT =
    Draft(to = listOf(EmailAddress(null, "zed-recipient@example.test")), subject = "quiet-subject", body = "plain-body-text")

private fun writer(directory: File) = OutboxWriter(directory, OutboxSealer.keyFor(DEVICE_KEY), "example.test")

private fun File.allBytes() = walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

class OutboxWriterTest {
    @Test
    fun `a message written with only the public key leaves no plaintext on disk`() =
        runTest {
            val directory = createTempDirectory().toFile()

            assertIs<WriteOutcome.Queued>(writer(directory).write(DRAFT))

            val bytes = directory.allBytes()
            listOf("zed-recipient", "quiet-subject", "plain-body-text").forEach { assertFalse(it in bytes, it) }
        }

    @Test
    fun `a message written while locked opens with the device key it was sealed to`() =
        runTest {
            val directory = createTempDirectory().toFile()
            val id = (writer(directory).write(DRAFT) as WriteOutcome.Queued).id

            val store = assertNotNull(OutboxStore.open(directory))
            val sealed = (store.readMessage(id) as MessageRead.Found).message
            val opened = assertIs<OpenResult.Opened>(OutboxSealer.open(id, sealed, DEVICE_KEY))
            assertEquals(DRAFT, opened.message.draft)
            assertEquals("$id@example.test", opened.message.messageId)
        }

    @Test
    fun `a message written while locked is sealed to a previous key once the device key changes`() =
        runTest {
            val directory = createTempDirectory().toFile()
            val id = (writer(directory).write(DRAFT) as WriteOutcome.Queued).id

            val store = assertNotNull(OutboxStore.open(directory))
            val sealed = (store.readMessage(id) as MessageRead.Found).message
            val result = assertIs<OpenResult.KeyMismatch>(OutboxSealer.open(id, sealed, OTHER_KEY))
            assertEquals(OutboxSealer.keyFor(DEVICE_KEY).fingerprint, result.sealedTo)
        }

    @Test
    fun `a message written while locked is listed as sealed to another key for a re-keyed device`() =
        runTest {
            val directory = createTempDirectory().toFile()
            writer(directory).write(DRAFT)

            val store = assertNotNull(OutboxStore.open(directory))
            val client = FakeJmapServer().withSendableAccount().client()
            val sender = OutboxSender(store, client, ComposeRepository(client, MailboxRepository(client)), OTHER_KEY)
            val outbox =
                Outbox(store, OutboxQueue(store, OutboxSealer.keyFor(OTHER_KEY), "example.test"), sender, OTHER_KEY, backgroundScope)
            val drained = outbox.drain()

            assertEquals(listOf(ItemResult.KeyMismatch(OutboxSealer.keyFor(DEVICE_KEY).fingerprint)), drained.map { it.result })
            assertEquals(listOf(Contents.SealedToOtherKey), outbox.entries.value.map { it.contents })
        }

    @Test
    fun `a message written while locked is sent once after the device key opens it`() =
        runTest {
            val directory = createTempDirectory().toFile()
            val fake = FakeJmapServer().withSendableAccount()
            val id = (writer(directory).write(DRAFT) as WriteOutcome.Queued).id

            val store = assertNotNull(OutboxStore.open(directory))
            val client = fake.client()
            val compose = ComposeRepository(client, MailboxRepository(client))
            val sender = OutboxSender(store, client, compose, DEVICE_KEY)
            val first = sender.drain()
            val second = sender.drain()

            assertEquals(listOf(Drained(id, ItemResult.Sent)), first)
            assertEquals(emptyList(), second)
            assertEquals(1, fake.submissions.size)
            assertEquals(listOf("$id@example.test"), fake.emails.single().messageId)
        }

    @Test
    fun `a write is refused while another store holds the directory`() =
        runTest {
            val directory = createTempDirectory().toFile()
            val held = assertNotNull(OutboxStore.open(directory))

            assertEquals(WriteOutcome.HeldElsewhere, writer(directory).write(DRAFT))
            held.close()
            assertIs<WriteOutcome.Queued>(writer(directory).write(DRAFT))
        }

    @Test
    fun `the waiting count needs no key and counts only messages not sent`() =
        runTest {
            val directory = createTempDirectory().toFile()
            writer(directory).write(DRAFT)
            writer(directory).write(DRAFT)
            assertEquals(OutboxWaiting(2, 0), waitingInOutbox(directory))

            val store = assertNotNull(OutboxStore.open(directory))
            val sent = store.list().items.first()
            store.finish(sent.copy(state = OutboxState.Sent("e1", 1)))
            store.close()

            assertEquals(OutboxWaiting(1, 0), waitingInOutbox(directory))
        }

    @Test
    fun `unconfirmed and rejected messages are counted as needing a decision, not waiting`() =
        runTest {
            val directory = createTempDirectory().toFile()
            repeat(4) { writer(directory).write(DRAFT) }
            val store = assertNotNull(OutboxStore.open(directory))
            val items = store.list().items
            store.save(items[0].copy(state = OutboxState.Unconfirmed))
            store.save(items[1].copy(state = OutboxState.Rejected(RejectionCategory.Refused)))
            store.save(items[2].copy(state = OutboxState.Submitting(1)))
            store.close()

            assertEquals(OutboxWaiting(waiting = 2, needDecision = 2), waitingInOutbox(directory))
        }

    @Test
    fun `a stored key that is not a public key does not decode`() {
        assertEquals(null, SealKey.decodeOrNull("not base64!"))
        assertEquals(null, SealKey.decodeOrNull("AAAA"))
        assertNotNull(SealKey.decodeOrNull(OutboxSealer.keyFor(DEVICE_KEY).encoded()))
    }

    @Test
    fun `the waiting count is unknown while another store holds the directory`() =
        runTest {
            val directory = createTempDirectory().toFile()
            val held = assertNotNull(OutboxStore.open(directory))

            assertEquals(null, waitingInOutbox(directory))
            held.close()
        }

    @Test
    fun `the writer holds a public key and a directory and nothing that can open or send`() {
        val held = OutboxWriter::class.java.declaredFields.map { it.type }
        assertTrue(held.none { it == ByteArray::class.java || it == JmapClient::class.java }, held.toString())
        assertContentEquals(
            listOf(File::class.java, SealKey::class.java, String::class.java, kotlin.time.Clock::class.java).map { it.name }.sorted(),
            held.map { it.name }.sorted(),
        )
    }
}
