package com.verdenroz.corvid

internal data class SseEvent(
    val id: String?,
    val event: String,
    val data: String,
)

/** Reads a `text/event-stream` one line at a time; a blank line completes the event, a comment is skipped. */
internal class SseParser {
    private var id: String? = null
    private var event = "message"
    private val data = StringBuilder()

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return complete()
        val colon = line.indexOf(':')
        if (colon == 0) return null
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> data.append(value).append('\n')
        }
        return null
    }

    private fun complete(): SseEvent? {
        val finished = if (data.isEmpty()) null else SseEvent(id, event, data.toString().removeSuffix("\n"))
        event = "message"
        data.clear()
        return finished
    }
}
