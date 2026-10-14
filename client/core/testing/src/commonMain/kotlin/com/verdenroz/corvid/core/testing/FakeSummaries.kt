package com.verdenroz.corvid.core.testing

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

private val JSON = headersOf(HttpHeaders.ContentType, listOf("application/json"))

/** `/corvid/summaries/{threadId}`: a 404 for a thread with none served, and a 503 while [off]. */
class FakeSummaries {
    private val served = ConcurrentHashMap<String, Pair<String, String>>()

    /** Answers the 503 a server gives when the feature is not enabled. */
    @Volatile
    var off: Boolean = false

    /** Drops each summary's `text`, as a body this app cannot read. */
    @Volatile
    var malformed: Boolean = false

    fun serve(
        threadId: String,
        text: String,
        generatedAt: String = "2024-01-02T03:04:05Z",
    ) {
        served[threadId] = text to generatedAt
    }

    internal fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        if (off) return scope.respond("""{"error":"summaries_off"}""", HttpStatusCode.ServiceUnavailable, JSON)
        val threadId = request.url.encodedPath.removePrefix("/corvid/summaries/")
        val (text, generatedAt) = served[threadId] ?: return scope.respond("not found", HttpStatusCode.NotFound)
        val body =
            buildJsonObject {
                put("threadId", threadId)
                if (!malformed) put("text", text)
                put("generatedAt", generatedAt)
            }
        return scope.respond(body.toString(), HttpStatusCode.OK, JSON)
    }
}
