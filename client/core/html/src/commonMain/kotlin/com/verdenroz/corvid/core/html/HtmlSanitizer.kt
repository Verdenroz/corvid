package com.verdenroz.corvid.core.html

import com.fleeksoft.ksoup.nodes.Element

private val DROPPED_TAGS =
    setOf(
        "head", "script", "style", "noscript", "template", "form", "input", "button", "select", "textarea", "option",
        "iframe", "frame", "frameset", "object", "embed", "applet", "svg", "math", "canvas", "audio", "video", "map",
        "meta", "link", "title", "base",
    )

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")
private val HIDING_STYLE = Regex("""display:none|visibility:hidden""")
private val WHITESPACE = Regex("""\s+""")

/** Everything not dropped is either rendered by tag or unwrapped to its children, so the allowlist is the converter's `when`. */
internal fun Element.isDropped(): Boolean = tagName() in DROPPED_TAGS || hasAttr("hidden") || hasHidingStyle()

private fun Element.hasHidingStyle(): Boolean = HIDING_STYLE.containsMatchIn(attr("style").lowercase().replace(WHITESPACE, ""))

internal fun safeLink(raw: String): String? {
    val href = raw.trim()
    return href.takeIf { it.substringBefore(':', "").lowercase() in OPENABLE_SCHEMES && it.contains(':') }
}

/** An image loads only from the user's own proxy path; [proxyBase] ends in `/i/`. */
internal fun imageUrl(
    src: String,
    proxyBase: String,
): String? = src.trim().takeIf { it.startsWith(proxyBase, ignoreCase = true) }
