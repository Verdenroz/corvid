package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.JmapJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** One path of the web tier under `/corvid`. Until something is served it answers 404, as an older server does. */
class FakeWebEndpoint {
    @Volatile
    private var answers: List<(Parameters) -> JsonObject> = emptyList()

    /** Answers every GET with a 500. */
    var broken: Boolean = false

    /** The next this many GETs fail as a dropped connection, the way a restarting server's do. */
    var dropped: Int = 0

    /** While set, a GET waits on it after reading the request, so the answer is as of arrival. */
    var gate: CompletableDeferred<Unit>? = null

    /** The query string of each GET, in order; empty when it had none. Read from test threads while requests land, so it is copy-on-write. */
    val reads: MutableList<String> = CopyOnWriteArrayList()

    /** The JSON body of each POST, in order. */
    val posts: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** The Authorization header the last request carried. */
    var authorization: String? = null
        private set

    /** How many requests have reached the path, answered or still waiting on [gate]. */
    @Volatile
    var arrivals: Int = 0
        private set

    fun serve(body: JsonObject) = serve { body }

    fun serve(answer: (Parameters) -> JsonObject) = serveEach(listOf(answer))

    /** Answers successive GETs from [answers] in order; the last stays. */
    fun serveEach(answers: List<(Parameters) -> JsonObject>) {
        this.answers = answers
    }

    internal suspend fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        authorization = request.headers[HttpHeaders.Authorization]
        arrivals++
        if (answers.isEmpty()) return scope.respond("not found", HttpStatusCode.NotFound)
        if (request.method == HttpMethod.Post) {
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            posts += JmapJson.parseToJsonElement(body) as JsonObject
            return scope.respond("", HttpStatusCode.Accepted)
        }
        if (broken) return scope.respond("", HttpStatusCode.InternalServerError)
        if (dropped > 0) {
            dropped--
            throw IOException("simulated restart")
        }
        reads += request.url.encodedQuery
        val body = answers.first()(request.url.parameters)
        if (answers.size > 1) answers = answers.drop(1)
        gate?.await()
        return scope.respond(body.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, listOf("application/json")))
    }
}
