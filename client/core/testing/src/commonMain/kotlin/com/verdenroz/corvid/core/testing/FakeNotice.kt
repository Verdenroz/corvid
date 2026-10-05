package com.verdenroz.corvid.core.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One `/corvid/notices` entry; [kind] is the wire spelling, so a test can send one the client has never seen. */
data class FakeNotice(
    val id: Long,
    val kind: String = "signIn",
    val at: String = "2026-10-04T09:30:00Z",
    val title: String = "New sign-in",
    val detail: String = "alice@corvid.test signed in from 203.0.113.9 via imaps.",
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("at", at)
            put("title", title)
            put("detail", detail)
        }
}
