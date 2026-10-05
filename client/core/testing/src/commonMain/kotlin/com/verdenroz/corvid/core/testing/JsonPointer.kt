package com.verdenroz.corvid.core.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Resolves a JMAP result reference's `path` against a prior call's result,
 * the way a real server does when it sees a `#`-prefixed argument. A `*`
 * segment maps the rest of the path over every element of the array at
 * that point, e.g. `list`, `*`, `id` pulled out of an `Email/get` result.
 * Arrays that mapping yields are flattened into one, as RFC 8620 says, so
 * `list`, `*`, `emailIds` over a `Thread/get` is one flat list of ids.
 */
fun resolveJsonPointer(
    root: JsonElement,
    path: String,
): JsonElement {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    return resolveSegments(root, segments)
}

private fun resolveSegments(
    value: JsonElement,
    segments: List<String>,
): JsonElement {
    if (segments.isEmpty()) return value
    val head = segments.first()
    val tail = segments.drop(1)
    if (head == "*") {
        val array = value as? JsonArray ?: return JsonNull
        return JsonArray(
            array.flatMap {
                when (val resolved = resolveSegments(it, tail)) {
                    is JsonArray -> resolved
                    else -> listOf(resolved)
                }
            },
        )
    }
    val next =
        when (value) {
            is JsonObject -> value[head] ?: JsonNull
            is JsonArray -> head.toIntOrNull()?.let { value.getOrNull(it) } ?: JsonNull
            else -> JsonNull
        }
    return resolveSegments(next, tail)
}
