package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Draft
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val OLD_DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }
private val NEW_DEVICE_KEY = ByteArray(33) { (it * 5 + 3).toByte() }

class OutboxSealKeyTest {
    private val store = assertNotNull(OutboxStore.open(createTempDirectory().toFile()))
    private val old = OutboxSealer.keyFor(OLD_DEVICE_KEY)
    private val current = OutboxSealer.keyFor(NEW_DEVICE_KEY)

    @Test
    fun `a first unlock stores the key its device key derives`() {
        val decision = OutboxSealer.sealKeyFor(OLD_DEVICE_KEY, stored = null, store)

        assertEquals(old.fingerprint, decision.sealTo.fingerprint)
        assertEquals(true, decision.persist)
    }

    @Test
    fun `an unchanged device key keeps the stored key`() {
        val decision = OutboxSealer.sealKeyFor(OLD_DEVICE_KEY, stored = old.encoded(), store)

        assertEquals(false, decision.persist)
    }

    @Test
    fun `a new device key replaces the stored key when nothing is sealed to the old one`() {
        val decision = OutboxSealer.sealKeyFor(NEW_DEVICE_KEY, stored = old.encoded(), store)

        assertEquals(current.fingerprint, decision.sealTo.fingerprint)
        assertEquals(true, decision.persist)
    }

    @Test
    fun `a new device key seals new mail to a key it can open while old mail stays sealed to the old one`() {
        val oldId = OutboxQueue(store, old, "example.test").enqueue(Draft(subject = "old"), null)
        val decision = OutboxSealer.sealKeyFor(NEW_DEVICE_KEY, stored = old.encoded(), store)
        val newId = OutboxQueue(store, decision.sealTo, "example.test").enqueue(Draft(subject = "new"), null)

        assertEquals(false, decision.persist)
        val fresh = assertIs<MessageRead.Found>(store.readMessage(newId)).message
        assertIs<OpenResult.Opened>(OutboxSealer.open(newId, fresh, NEW_DEVICE_KEY))
        val stale = assertIs<MessageRead.Found>(store.readMessage(oldId)).message
        assertIs<OpenResult.KeyMismatch>(OutboxSealer.open(oldId, stale, NEW_DEVICE_KEY))
    }
}
