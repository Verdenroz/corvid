package com.verdenroz.corvid.core.keyring

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KeyHolderTest {
    @Test
    fun `starts locked`() {
        val holder = KeyHolder()

        assertEquals(KeyState.Locked, holder.state.value)
    }

    @Test
    fun `unlock exposes exactly the bytes it was given`() {
        val holder = KeyHolder()
        val key = ByteArray(32) { it.toByte() }

        holder.unlock(key)

        val unlocked = assertIs<KeyState.Unlocked>(holder.state.value)
        assertContentEquals(key, unlocked.key)
    }

    @Test
    fun `drop zeroes the array and returns to locked`() {
        val holder = KeyHolder()
        val key = ByteArray(32) { 0x42 }
        holder.unlock(key)

        holder.drop()

        assertEquals(KeyState.Locked, holder.state.value)
        assertContentEquals(ByteArray(32), key)
    }
}
