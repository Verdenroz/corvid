package com.verdenroz.corvid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun events(vararg lines: String): List<SseEvent> {
    val parser = SseParser()
    return lines.mapNotNull(parser::feed)
}

class SseParserTest {
    @Test
    fun `a blank line completes an event with its id, name and data`() {
        val read = events("id: 17", "event: push", "data: abc", "")

        assertEquals(listOf(SseEvent("17", "push", "abc")), read)
    }

    @Test
    fun `a keepalive comment is skipped and completes nothing`() {
        assertEquals(emptyList(), events(": keepalive", ""))
    }

    @Test
    fun `an event with no name is a message and the next one starts clean`() {
        val read = events("id: 1", "event: push", "data: a", "", "id: 2", "data: b", "")

        assertEquals(listOf(SseEvent("1", "push", "a"), SseEvent("2", "message", "b")), read)
    }

    @Test
    fun `data on several lines joins with newlines`() {
        assertEquals(listOf(SseEvent(null, "message", "a\nb")), events("data: a", "data: b", ""))
    }

    @Test
    fun `a partial event is not complete until its blank line`() {
        assertNull(SseParser().feed("data: a"))
    }
}
