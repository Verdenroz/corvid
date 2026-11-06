package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }
private val OTHER_KEY = ByteArray(33) { (it * 5 + 3).toByte() }

private val MESSAGE =
    OutboxMessage(
        draft =
            Draft(
                to = listOf(EmailAddress("Ada Lovelace", "ada@analytical.test")),
                cc = listOf(EmailAddress(null, "babbage@engine.test")),
                subject = "Notes on the engine",
                body = "The sealed body mentions Bernoulli numbers.",
                inReplyTo = listOf("<prior@engine.test>"),
                attachments = listOf(Attachment("b1", "table.pdf", "application/pdf", 12)),
                from = "alias@mail.test",
            ),
        replacesDraftId = "d9",
        messageId = "m1@mail.test",
        keyword = "corvid-outbox-m1",
        createdAt = 1234,
    )

class OutboxSealerTest {
    private val key = OutboxSealer.keyFor(DEVICE_KEY)

    @Test
    fun `a message opens to what was sealed with the same device key`() {
        val sealed = OutboxSealer.seal("m1", MESSAGE, key)

        val opened = assertIs<OpenResult.Opened>(OutboxSealer.open("m1", sealed, DEVICE_KEY)).message

        assertEquals(MESSAGE, opened)
    }

    @Test
    fun `a message sealed to one key reports a key mismatch under another and not corruption`() {
        val sealed = OutboxSealer.seal("m1", MESSAGE, key)

        val result = assertIs<OpenResult.KeyMismatch>(OutboxSealer.open("m1", sealed, OTHER_KEY))

        assertEquals(key.fingerprint, result.sealedTo)
    }

    @Test
    fun `a flipped byte in the ciphertext is corrupt`() {
        val sealed = OutboxSealer.seal("m1", MESSAGE, key)
        val flipped = sealed.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertEquals(OpenResult.Corrupt, OutboxSealer.open("m1", SealedMessage(sealed.keyFingerprint, flipped), DEVICE_KEY))
    }

    @Test
    fun `a payload that decrypts but cannot be decoded is corrupt`() {
        val sealed = OutboxSealer.sealPayload("m1", "not a message".toByteArray(), key)

        assertEquals(OpenResult.Corrupt, OutboxSealer.open("m1", sealed, DEVICE_KEY))
    }

    @Test
    fun `a sealed message does not open under another id`() {
        val sealed = OutboxSealer.seal("m1", MESSAGE, key)

        assertEquals(OpenResult.Corrupt, OutboxSealer.open("m2", sealed, DEVICE_KEY))
    }

    @Test
    fun `the public key is the same on every derivation and survives its stored form`() {
        assertEquals(key.fingerprint, OutboxSealer.keyFor(DEVICE_KEY).fingerprint)
        assertEquals(key.fingerprint, SealKey.decode(key.encoded()).fingerprint)
        assertFalse(key.fingerprint == OutboxSealer.keyFor(OTHER_KEY).fingerprint)
    }

    @Test
    fun `a message sealed to a stored public key opens with the device key it came from`() {
        val sealed = OutboxSealer.seal("m1", MESSAGE, SealKey.decode(key.encoded()))

        assertIs<OpenResult.Opened>(OutboxSealer.open("m1", sealed, DEVICE_KEY))
    }

    @Test
    fun `a message sealed on disk contains none of its recipients, subject or body in plaintext`() {
        val directory = createTempDirectory().toFile()
        val store = assertNotNull(OutboxStore.open(directory))
        store.use { it.enqueue("m1", OutboxSealer.seal("m1", MESSAGE, key), createdAt = 1234) }

        val onDisk = directory.walkTopDown().filter(File::isFile).map { it.readBytes().decodeToString() + it.name }.joinToString("\n")

        listOf(
            "ada@analytical.test",
            "Ada Lovelace",
            "babbage@engine.test",
            "Notes on the engine",
            "Bernoulli",
            "alias@mail.test",
        ).forEach { assertFalse(onDisk.contains(it), "$it is readable on disk") }
        assertTrue(directory.list().orEmpty().none { it.endsWith(".tmp") })
    }
}
