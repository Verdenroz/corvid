package com.verdenroz.corvid.core.data.outbox

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxStoreTest {
    private val directory = createTempDirectory().toFile()
    private val sealed = SealedMessage("fp1", byteArrayOf(1, 2, 3, 4))

    private fun store() = assertNotNull(OutboxStore.open(directory))

    @Test
    fun `a queued message reads back oldest first with its sealed bytes`() {
        store().use {
            it.enqueue("b", sealed, createdAt = 20)
            it.enqueue("a", sealed, createdAt = 10)

            assertEquals(listOf("a", "b"), it.list().items.map(OutboxItem::id))
            val read = assertIs<MessageRead.Found>(it.readMessage("a"))
            assertContentEquals(sealed.bytes, read.message.bytes)
            assertEquals("fp1", read.message.keyFingerprint)
        }
    }

    @Test
    fun `a saved state survives reopening the directory`() {
        store().use {
            val item = it.enqueue("a", sealed, createdAt = 10)
            it.save(item.copy(attempt = 2, state = OutboxState.Submitting(startedAt = 99)))
        }

        store().use {
            val item = it.list().items.single()
            assertEquals(2, item.attempt)
            assertEquals(OutboxState.Submitting(99), item.state)
        }
    }

    @Test
    fun `a corrupt state file is quarantined and reported rather than read as an empty outbox`() {
        store().use {
            it.enqueue("a", sealed, createdAt = 10)
            File(directory, "a.state").writeText("{ not json")

            val listing = it.list()

            assertTrue(listing.items.isEmpty())
            assertEquals(listOf<OutboxFault>(OutboxFault.Corrupt("a", "a.state.bad")), listing.faults)
            assertTrue(File(directory, "a.state.bad").exists())
            assertTrue(File(directory, "a.msg.bad").exists())
            assertTrue(it.list().faults.isEmpty())
        }
    }

    @Test
    fun `a state file from another schema is refused the same way`() {
        store().use {
            it.enqueue("a", sealed, createdAt = 10)
            val text = File(directory, "a.state").readText().replace("\"schema\":1", "\"schema\":9")
            File(directory, "a.state").writeText(text)

            val listing = it.list()

            assertTrue(listing.items.isEmpty())
            assertEquals(listOf<OutboxFault>(OutboxFault.ForeignSchema("a", "a.state.bad", "9")), listing.faults)
        }
    }

    @Test
    fun `a state file with a higher schema and an extra field is another schema and keeps its message paired`() {
        store().use {
            it.enqueue("a", sealed, createdAt = 10)
            val text = File(directory, "a.state").readText().replace("\"schema\":1", "\"schema\":2,\"extra\":true")
            File(directory, "a.state").writeText(text)

            val listing = it.list()

            assertEquals(listOf<OutboxFault>(OutboxFault.ForeignSchema("a", "a.state.bad", "2")), listing.faults)
            assertEquals(setOf("a.state.bad", "a.msg.bad", ".lock"), directory.list().orEmpty().toSet())
        }
    }

    @Test
    fun `a message whose bytes changed on disk is quarantined as corrupt`() {
        store().use {
            it.enqueue("a", sealed, createdAt = 10)
            val lines = File(directory, "a.msg").readLines().toMutableList()
            lines[3] = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))
            File(directory, "a.msg").writeText(lines.joinToString("\n"))

            assertEquals(OutboxFault.Corrupt("a", "a.msg.bad"), assertIs<MessageRead.Faulty>(it.readMessage("a")).fault)
            assertEquals(MessageRead.Missing, it.readMessage("a"))
        }
    }

    @Test
    fun `a message from another envelope schema is refused and quarantined`() {
        store().use {
            it.enqueue("a", sealed, createdAt = 10)
            File(directory, "a.msg").writeText(File(directory, "a.msg").readText().replaceFirst("corvid-outbox 1", "corvid-outbox 2"))

            val read = assertIs<MessageRead.Faulty>(it.readMessage("a"))

            assertEquals(OutboxFault.ForeignSchema("a", "a.msg.bad", "2"), read.fault)
        }
    }

    @Test
    fun `finishing an item deletes its message and keeps a tombstone`() {
        store().use {
            val item = it.enqueue("a", sealed, createdAt = 10)
            it.finish(item.copy(state = OutboxState.Sent("e1", at = 50)))

            assertEquals(MessageRead.Missing, it.readMessage("a"))
            assertEquals(OutboxState.Sent("e1", 50), it.list().items.single().state)
        }
    }

    @Test
    fun `no temp file is left behind after a write`() {
        store().use { it.enqueue("a", sealed, createdAt = 10) }

        assertTrue(directory.list().orEmpty().none { it.endsWith(".tmp") })
    }

    @Test
    fun `two stores cannot hold one directory at once`() {
        store().use {
            assertNull(OutboxStore.open(directory))
        }

        store().close()
    }
}
