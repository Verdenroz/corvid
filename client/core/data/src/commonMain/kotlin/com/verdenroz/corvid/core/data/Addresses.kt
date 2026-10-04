package com.verdenroz.corvid.core.data

private val QUOTED_NAME_CHARS = charArrayOf(',', ';', '<', '>', '"', '(', ')')

internal fun emailOf(address: String): String =
    if ('<' in address) address.substringAfterLast('<').substringBefore('>').trim() else address.trim()

internal fun nameOf(address: String): String = if ('<' in address) unquote(address.substringBeforeLast('<').trim()) else ""

internal fun formatAddress(
    name: String?,
    email: String,
): String =
    when {
        name == null -> email
        name.any { it in QUOTED_NAME_CHARS } -> "\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\" <$email>"
        else -> "$name <$email>"
    }

/** Splits a recipient field on commas and semicolons that sit outside quotes and angle brackets. */
fun splitAddresses(text: String): List<String> {
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
