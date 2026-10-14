package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.ThreadSummary
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
private class WireSummary(val text: String, val generatedAt: String)

/** The server's thread summaries, behind `/corvid/summaries/{threadId}` on the web tier. */
class SummaryRepository(private val jmapClient: JmapClient) {
    /** Null when the thread has no summary, the server lacks the route, or it has the feature off. */
    suspend fun get(threadId: String): ThreadSummary? {
        val body = jmapClient.webGetOrNull("/corvid/summaries/$threadId") ?: return null
        return body.decodeWeb(WireSummary.serializer(), "Summary") { ThreadSummary(it.text, Instant.parse(it.generatedAt)) }
    }
}
