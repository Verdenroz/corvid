package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Rewrite

/**
 * The milter adds one `X-Corvid-Rewrite` to every message it sees and never removes a copy the
 * sender supplied, so two values mean one is forged and neither can be trusted. Null when there
 * is none, several, or one this client cannot read: a missing header means the milter never ran.
 */
fun readRewrite(values: List<String>): Rewrite? = values.singleOrNull()?.let(::parseRewrite)

/** Mirrors `Marked::parse` in crates/corvid-rewrite/src/header.rs, field for field. */
internal fun parseRewrite(value: String): Rewrite? {
    val trimmed = value.trim()
    if (trimmed == "none") return Rewrite.Clean
    if (trimmed == "skipped") return Rewrite.Skipped
    val counts = mutableMapOf<String, Int>()
    var named = emptySet<String>()
    var omitted = 0
    for (field in trimmed.split(WHITESPACE)) {
        val (key, raw) = field.split('=', limit = 2).takeIf { it.size == 2 } ?: return null
        when (key) {
            "stripped", "proxied", "hosts", "trackers" -> counts[key] = raw.toCount() ?: return null
            "tracker-hosts" -> named = raw.split(',').toSet()
            "tracker-hosts-omitted" -> omitted = raw.toCount() ?: return null
            else -> return null
        }
    }
    return Rewrite.Stripped(
        stripped = counts["stripped"] ?: return null,
        proxied = counts["proxied"] ?: return null,
        hosts = counts["hosts"] ?: return null,
        trackers = counts["trackers"] ?: return null,
        named = named,
        omitted = omitted,
    )
}

private fun String.toCount(): Int? = toIntOrNull()?.takeIf { it >= 0 }
