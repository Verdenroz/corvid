package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.ThreadSummary
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private val ABSENT = setOf(HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)

@Serializable
private class WireSummary(val text: String, val generatedAt: String)

/** The server's thread summaries, behind `/corvid/summaries/{threadId}` on the web tier. */
class SummaryRepository(private val jmapClient: JmapClient) {
    /** Null when the thread has no summary, the server lacks the route, or it has the feature off. */
    suspend fun get(threadId: String): ThreadSummary? {
        val reply = jmapClient.webSend(HttpMethod.Get, "/corvid/summaries/$threadId", passThrough = ABSENT)
        val body = reply.body?.takeIf { reply.status == HttpStatusCode.OK } ?: return null
        return body.decodeWeb(WireSummary.serializer(), "Summary") { ThreadSummary(it.text, Instant.parse(it.generatedAt)) }
    }
}
