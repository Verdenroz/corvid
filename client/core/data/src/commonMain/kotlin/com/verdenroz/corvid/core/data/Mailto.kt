package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ComposeIntent

/** RFC 6068 `mailto:addr?subject=...`, as `List-Unsubscribe` offers it; null for anything that is not one. */
fun mailtoIntent(url: String): ComposeIntent.To? {
    if (!url.startsWith("mailto:", ignoreCase = true)) return null
    val rest = url.substring("mailto:".length)
    val address = percentDecoded(rest.substringBefore('?')).takeIf { '@' in it } ?: return null
    val subject =
        rest.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=').equals("subject", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let(::percentDecoded)
            .orEmpty()
    return ComposeIntent.To(address, subject)
}

private fun percentDecoded(text: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < text.length) {
        val char = text[i]
        if (char == '%' && i + 2 <= text.lastIndex) {
            text.substring(i + 1, i + 3).toIntOrNull(16)?.let {
                bytes += it.toByte()
                i += 3
                continue
            }
        }
        bytes += char.toString().encodeToByteArray().toList()
        i++
    }
    return bytes.toByteArray().decodeToString()
}
