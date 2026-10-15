package com.verdenroz.corvid.core.testing

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.CopyOnWriteArrayList

private val JSON = headersOf(HttpHeaders.ContentType, listOf("application/json"))

/** One item of a [FakeDigest]; [summary] is left off the wire when null. */
data class FakeDigestItem(
    val threadId: String = "t1",
    val emailId: String = "e1",
    val from: String = "Ada <ada@x.test>",
    val subject: String = "Lunch",
    val receivedAt: String = "2024-05-06T07:08:09Z",
    val summary: String? = null,
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
            put("emailId", emailId)
            put("from", from)
            put("subject", subject)
            put("receivedAt", receivedAt)
            summary?.let { put("summary", it) }
        }
}

/** One `/corvid/digest` document; a null group label goes on the wire as null. */
data class FakeDigest(
    val id: String,
    val from: String = "2024-05-05T07:00:00Z",
    val to: String = "2024-05-06T07:00:00Z",
    val groups: List<Pair<String?, List<FakeDigestItem>>> = emptyList(),
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("from", from)
            put("to", to)
            putJsonArray("groups") {
                groups.forEach { (label, items) ->
                    add(
                        buildJsonObject {
                            put("label", label)
                            putJsonArray("items") { items.forEach { add(it.toJson()) } }
                        },
                    )
                }
            }
        }
}

/** `/corvid/digest/{id}` and `latest`: a 404 until [serve] is called, as an older server answers, and a 503 while [off]. */
class FakeDigests {
    private val served = CopyOnWriteArrayList<FakeDigest>()

    /** Answers the 503 a server gives when the feature is not enabled. */
    @Volatile
    var off: Boolean = false

    /** Drops each digest's `groups`, as a body this app cannot read. */
    @Volatile
    var malformed: Boolean = false

    /** Each digest as it is served, the newest last. */
    fun serve(vararg digests: FakeDigest) {
        served += digests
    }

    internal fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        if (off) return scope.respond("""{"error":"digest_off"}""", HttpStatusCode.ServiceUnavailable, JSON)
        val tail = request.url.encodedPath.removePrefix("/corvid/digest").trim('/')
        val digest =
            (if (tail == "latest") served.lastOrNull() else served.firstOrNull { it.id == tail })
                ?: return scope.respond("not found", HttpStatusCode.NotFound)
        val json = digest.toJson().let { if (malformed) JsonObject(it - "groups") else it }
        return scope.respond(json.toString(), HttpStatusCode.OK, JSON)
    }
}
