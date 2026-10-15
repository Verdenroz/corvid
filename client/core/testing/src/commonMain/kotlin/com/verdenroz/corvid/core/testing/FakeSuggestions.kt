package com.verdenroz.corvid.core.testing

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList

private val JSON = headersOf(HttpHeaders.ContentType, listOf("application/json"))

/** One `/corvid/suggestions` entry; [senderKind] and [actionKind] are the wire spellings. */
data class FakeSuggestion(
    val id: String,
    val senderKind: String = "from",
    val sender: String = "news@x.test",
    val actionKind: String = "archive",
    val mailboxId: String? = null,
    val settled: Int = 23,
    val hits: Int = 24,
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            putJsonObject("sender") {
                put("kind", senderKind)
                put("value", sender)
            }
            putJsonObject("action") {
                put("kind", actionKind)
                mailboxId?.let { put("mailboxId", it) }
            }
            put("settled", settled)
            put("hits", hits)
        }
}

/** `/corvid/suggestions`: a 404 until [serve] is called, as an older server answers, and a 503 while [off]. */
class FakeSuggestions {
    @Volatile
    private var served: List<FakeSuggestion>? = null

    /** Answers the 503 a server gives when the feature is not enabled. */
    @Volatile
    var off: Boolean = false

    /** Drops each suggestion's `action`, as a body this app cannot read. */
    @Volatile
    var malformed: Boolean = false

    /** Answers each accept and dismiss with a 500. */
    @Volatile
    var refusePosts: Boolean = false

    val accepted: MutableList<String> = CopyOnWriteArrayList()
    val dismissed: MutableList<String> = CopyOnWriteArrayList()

    fun serve(suggestions: List<FakeSuggestion>) {
        served = suggestions
    }

    fun serve(vararg suggestions: FakeSuggestion) = serve(suggestions.toList())

    internal fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        val feed = served ?: return scope.respond("not found", HttpStatusCode.NotFound)
        if (off) return scope.respond("""{"error":"suggestions_off"}""", HttpStatusCode.ServiceUnavailable, JSON)
        if (request.method == HttpMethod.Get) {
            val body =
                buildJsonObject {
                    putJsonArray(
                        "suggestions",
                    ) { feed.forEach { add(if (malformed) JsonObject(it.toJson() - "action") else it.toJson()) } }
                }
            return scope.respond(body.toString(), HttpStatusCode.OK, JSON)
        }
        if (refusePosts) return scope.respond("", HttpStatusCode.InternalServerError)
        val (id, verb) = request.url.encodedPath.removePrefix("/corvid/suggestions/").split("/")
        if (feed.none { it.id == id }) return scope.respond("not found", HttpStatusCode.NotFound)
        (if (verb == "accept") accepted else dismissed) += id
        return scope.respond("", HttpStatusCode.NoContent)
    }
}
