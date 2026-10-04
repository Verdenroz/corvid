package com.verdenroz.corvid.core.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.requestedHeaders(): List<String> =
    get("properties")?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it.startsWith("header:") }.orEmpty()

/**
 * RFC 8621 `header:{name}[:asText|:asURLs][:all]`. Without `:all` only the last instance is
 * returned, and a field the message lacks is null (or an empty list with `:all`).
 */
internal fun FakeEmail.headerValue(property: String): JsonElement {
    val parts = property.split(':')
    val values = headers.entries.firstOrNull { it.key.equals(parts[1], ignoreCase = true) }?.value.orEmpty()
    val form = parts.getOrNull(2)?.takeIf { it != "all" }
    val rendered = values.map { it.render(form) }
    return if (parts.last() == "all") JsonArray(rendered) else rendered.lastOrNull() ?: JsonNull
}

private fun String.render(form: String?): JsonElement =
    when (form) {
        "asURLs" -> JsonArray(Regex("<([^>]*)>").findAll(this).map { JsonPrimitive(it.groupValues[1]) }.toList())
        "asText" -> JsonPrimitive(trim())
        else -> JsonPrimitive(" $this")
    }

/** `inMailbox`, `from`, `to`, `cc` and `bcc` (a case-insensitive substring of any address, as a server matches text), and AND/OR/NOT. */
internal fun FakeEmail.matches(filter: JsonObject?): Boolean {
    if (filter == null) return true
    filter["operator"]?.jsonPrimitive?.content?.let { operator ->
        val results = filter.getValue("conditions").jsonArray.map { matches(it.jsonObject) }
        return when (operator) {
            "AND" -> results.all { it }
            "OR" -> results.any { it }
            else -> results.none { it }
        }
    }
    return filter.entries.all { (key, value) ->
        val wanted = value.jsonPrimitive.contentOrNull.orEmpty()
        when (key) {
            "inMailbox" -> wanted in mailboxIds
            "from" -> listOfNotNull(from, fromName).any { it.contains(wanted, ignoreCase = true) }
            "to" -> to.any { it.contains(wanted, ignoreCase = true) }
            "cc" -> cc.any { it.contains(wanted, ignoreCase = true) }
            "bcc" -> bcc.any { it.contains(wanted, ignoreCase = true) }
            else -> error("unsupported filter $key")
        }
    }
}
