package com.verdenroz.corvid.feature.compose

import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.parseAddresses

/** The bounds of the one address in a recipient field that [caret] sits in, split as [parseAddresses] splits it. */
internal class AddressSpan(private val text: String, private val caret: Int) {
    private val separators = separators(text)
    private val start = (separators.lastOrNull { it < caret } ?: -1) + 1
    private val end = separators.firstOrNull { it >= caret } ?: text.length

    val fragment: String get() = text.substring(start, caret).trim()

    val others: List<EmailAddress> get() = parseAddresses(text.removeRange(start, end))

    /** The field with this address replaced by [address], followed by a separator ready for the next one. */
    fun replacedBy(address: EmailAddress): String {
        val before = text.substring(0, start)
        val after = text.substring(end)
        val lead = if (before.isEmpty()) "" else " "
        return before + lead + address.toText() + after.ifEmpty { ", " }
    }
}

private fun separators(text: String): List<Int> {
    val found = mutableListOf<Int>()
    var quoted = false
    var escaped = false
    var angle = false
    text.forEachIndexed { index, char ->
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            !quoted && char == '<' -> angle = true
            !quoted && char == '>' -> angle = false
            !quoted && !angle && (char == ',' || char == ';') -> found += index
        }
    }
    return found
}
