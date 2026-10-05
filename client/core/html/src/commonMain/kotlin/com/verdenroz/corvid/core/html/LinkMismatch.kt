package com.verdenroz.corvid.core.html

private val URL_LIKE = Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?::\d+)?(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)

/** A phishing tell: the link reads as one address and goes to another. */
internal fun mismatchedHost(
    visibleText: String,
    href: String,
): Boolean {
    val shown = URL_LIKE.matchEntire(visibleText.trim())?.groupValues?.get(1) ?: return false
    val target = hostOf(href) ?: return false
    return shown.lowercase().removePrefix("www.") != target.removePrefix("www.")
}

private fun hostOf(href: String): String? {
    if (!href.contains("://")) return null
    val authority = href.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.substringAfterLast('@').substringBefore(':').lowercase().ifEmpty { null }
}
