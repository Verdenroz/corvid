package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.resultReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class QueryAndGet(val query: MethodOutcome, val get: MethodOutcome)

/** An Email/query and an Email/get of the ids it found, in one request; the server resolves the ids itself. */
internal suspend fun JmapClient.queryAndGet(
    query: JsonObject,
    properties: List<String>,
): QueryAndGet {
    val get =
        buildJsonObject {
            put("#ids", resultReference(resultOf = "c0", name = "Email/query", path = "/ids"))
            put("properties", JsonArray(properties.map(::JsonPrimitive)))
        }
    val outcomes = call(listOf(Invocation("Email/query", query, "c0"), Invocation("Email/get", get, "c1")))
    return QueryAndGet(outcomes.getValue("c0"), outcomes.getValue("c1"))
}
