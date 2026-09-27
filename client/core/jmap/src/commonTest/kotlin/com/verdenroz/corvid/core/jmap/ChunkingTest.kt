package com.verdenroz.corvid.core.jmap

import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkingTest {
    @Test
    fun `items split into groups no larger than the limit`() {
        val chunks = chunkedForLimit((1..5).toList(), maxSize = 2)

        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), chunks)
    }

    @Test
    fun `a session with no advertised limit gets one unsplit group`() {
        val chunks = chunkedForLimit((1..5).toList(), maxSize = null)

        assertEquals(listOf((1..5).toList()), chunks)
    }

    @Test
    fun `an empty list produces no chunks at all`() {
        assertEquals(emptyList(), chunkedForLimit(emptyList<Int>(), maxSize = 2))
    }
}
