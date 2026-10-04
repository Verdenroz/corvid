package com.verdenroz.corvid.core.model

private val NEEDS_QUOTES = charArrayOf(',', ';', '<', '>', '"')

data class EmailAddress(val name: String?, val email: String) {
    /** `Name <email>`, with the name quoted when it holds a character that would split or end the address. */
    fun toText(): String =
        when {
            name.isNullOrEmpty() -> email
            name.any { it in NEEDS_QUOTES } -> "\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\" <$email>"
            else -> "$name <$email>"
        }
}

/** Parses a recipient field: addresses separated by commas or semicolons outside quotes and angle brackets. */
fun parseAddresses(text: String): List<EmailAddress> = splitAddresses(text).map(::parseAddress)

private fun parseAddress(text: String): EmailAddress =
    if ('<' in text) {
        EmailAddress(
            name = unquote(text.substringBeforeLast('<').trim()).ifEmpty { null },
            email = text.substringAfterLast('<').substringBefore('>').trim(),
        )
    } else {
        EmailAddress(name = null, email = text.trim())
    }

private fun splitAddresses(text: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    var angle = false
    for (char in text) {
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            !quoted && char == '<' -> angle = true
            !quoted && char == '>' -> angle = false
            !quoted && !angle && (char == ',' || char == ';') -> {
                parts += current.toString()
                current.clear()
                continue
            }
        }
        current.append(char)
    }
    parts += current.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun unquote(name: String): String =
    if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
        name.substring(1, name.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    } else {
        name
    }
