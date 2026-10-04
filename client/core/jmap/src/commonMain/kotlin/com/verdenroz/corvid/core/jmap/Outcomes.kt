package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun MethodOutcome.resultOrThrow(): JsonObject =
    when (this) {
        is MethodOutcome.Success -> result
        is MethodOutcome.Failure -> throw JmapException(error)
    }

/** One method, one response: the shape every plain /get and /query caller wants. */
suspend fun JmapClient.callOne(
    method: String,
    arguments: JsonObject,
): JsonObject = call(listOf(Invocation(method, arguments, "c0"))).getValue("c0").resultOrThrow()

val JmapError.Method.reason: String get() = description ?: type

/** Why a /set refused [id] under [property] (`notCreated`, `notUpdated` or `notDestroyed`), or null if it did not. */
fun JsonObject.setErrorReason(
    property: String,
    id: String,
): String? {
    val problem = get(property)?.jsonObject?.get(id)?.jsonObject ?: return null
    return problem["description"]?.jsonPrimitive?.contentOrNull ?: problem.getValue("type").jsonPrimitive.content
}
