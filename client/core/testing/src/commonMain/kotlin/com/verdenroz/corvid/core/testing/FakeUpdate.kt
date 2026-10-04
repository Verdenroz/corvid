package com.verdenroz.corvid.core.testing

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One `/corvid/update` answer; [result] is the wire spelling, so a test can send one the client has never seen. */
data class FakeUpdate(
    val running: String = "0.2.0",
    val latest: String? = null,
    val checkedAt: String? = null,
    val result: String = "current",
    val detail: String? = null,
    val auto: Boolean = true,
    val busy: Boolean = false,
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put("running", running)
            put("latest", latest?.let(::JsonPrimitive) ?: JsonNull)
            put("checkedAt", checkedAt?.let(::JsonPrimitive) ?: JsonNull)
            put("result", result)
            put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
            put("auto", auto)
            put("busy", busy)
        }
}
