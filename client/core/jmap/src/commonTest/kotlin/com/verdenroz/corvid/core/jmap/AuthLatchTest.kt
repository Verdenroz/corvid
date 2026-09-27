package com.verdenroz.corvid.core.jmap

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthLatchTest {
    @Test
    fun `starts clear and trips on demand`() {
        val latch = AuthLatch()
        assertFalse(latch.tripped.value)

        latch.trip()

        assertTrue(latch.tripped.value)
    }

    @Test
    fun `clear undoes a trip after an interactive sign-in`() {
        val latch = AuthLatch()
        latch.trip()

        latch.clear()

        assertFalse(latch.tripped.value)
    }
}
