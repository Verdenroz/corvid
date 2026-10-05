package com.verdenroz.corvid.core.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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

/** Serves the notices newer than the request's `after`, oldest first. */
fun FakeWebEndpoint.serveNotices(feed: List<FakeNotice>) =
    serve { params ->
        val after = params["after"]?.toLong() ?: 0
        buildJsonObject { putJsonArray("notices") { feed.filter { it.id > after }.forEach { add(it.toJson()) } } }
    }
