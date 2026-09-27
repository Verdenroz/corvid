package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A JMAP result reference: put under a `#`-prefixed key (e.g. `#ids`) in
 * place of the literal argument, so the server fills it in from an earlier
 * call's result in the same request. The server resolves this, not the
 * client; [Invocation.arguments] just needs to carry the right shape.
 */
fun resultReference(
    resultOf: String,
    name: String,
    path: String,
): JsonObject =
    buildJsonObject {
        put("resultOf", resultOf)
        put("name", name)
        put("path", path)
    }
