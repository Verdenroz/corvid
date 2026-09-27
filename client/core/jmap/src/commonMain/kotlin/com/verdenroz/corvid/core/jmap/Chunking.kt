package com.verdenroz.corvid.core.jmap

/**
 * Splits a list into groups no larger than [maxSize], for the session's
 * `maxObjectsInGet` (ids going into a `Foo/get`) or `maxCallsInRequest`
 * (invocations going into one HTTP request). A session that never
 * advertised a limit gets one unsplit group: a server that omits the
 * property has no bound to respect.
 */
fun <T> chunkedForLimit(
    items: List<T>,
    maxSize: Int?,
): List<List<T>> =
    when {
        items.isEmpty() -> emptyList()
        maxSize == null || maxSize <= 0 -> listOf(items)
        else -> items.chunked(maxSize)
    }
