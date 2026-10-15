package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.Digest
import com.verdenroz.corvid.core.model.DigestEntry
import com.verdenroz.corvid.core.model.DigestGroup
import com.verdenroz.corvid.core.model.DigestItem
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Instant

private const val DIGEST_PATH = "/corvid/digest"
private val ABSENT_STATUSES = setOf(HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)

@Serializable
private class WireDigest(val id: String, val from: String, val to: String, val groups: List<WireGroup>)

@Serializable
private class WireGroup(val label: String? = null, val items: List<WireItem>)

@Serializable
private class WireItem(
    val threadId: String,
    val emailId: String,
    val from: String,
    val subject: String,
    val receivedAt: String,
    val summary: String? = null,
)

@Serializable
private class WireDigests(val digests: List<WireEntry>)

@Serializable
private class WireEntry(val id: String, val to: String, val count: Int)

/** The server's digests, behind `/corvid/digest` on the web tier. */
class DigestRepository(private val jmapClient: JmapClient) {
    @Volatile
    private var kindsUnsupported = false

    /** Null when no digest was made yet, the server lacks the route, or it has the feature off. */
    suspend fun latest(): Digest? = read("$DIGEST_PATH/latest")

    /** Null when the server holds no digest by [id]. */
    suspend fun get(id: String): Digest? = read("$DIGEST_PATH/$id")

    /** Empty when the server lacks the route or has the feature off. */
    suspend fun list(): List<DigestEntry> {
        val body = jmapClient.webGetOrNull(DIGEST_PATH) ?: return emptyList()
        return body.decodeWeb(WireDigests.serializer(), "Digests") { wire ->
            wire.digests.map { DigestEntry(it.id, Instant.parse(it.to), it.count) }
        }
    }

    /** Once the server answers 404 it is not asked again, as an older server has no such route. */
    suspend fun askForDigestPush(deviceName: String) {
        if (kindsUnsupported) return
        val body = buildJsonObject { putJsonArray("kinds") { add("digest") } }
        val reply = jmapClient.webSend(HttpMethod.Put, "/corvid/devices/$deviceName/push/kinds", body, passThrough = ABSENT_STATUSES)
        if (reply.status == HttpStatusCode.NotFound) kindsUnsupported = true
    }

    private suspend fun read(path: String): Digest? {
        val body = jmapClient.webGetOrNull(path) ?: return null
        return body.decodeWeb(WireDigest.serializer(), "Digest") { it.toDigest() }
    }
}

private fun WireDigest.toDigest() =
    Digest(id, Instant.parse(from), Instant.parse(to), groups.map { DigestGroup(it.label, it.items.map(WireItem::toItem)) })

private fun WireItem.toItem() = DigestItem(threadId, emailId, from, subject, Instant.parse(receivedAt), summary)
