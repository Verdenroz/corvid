package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.Digest
import com.verdenroz.corvid.core.model.DigestGroup
import com.verdenroz.corvid.core.model.DigestItem
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private const val DIGEST_PATH = "/corvid/digest"

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

/** The server's digests, behind `/corvid/digest` on the web tier. */
class DigestRepository(private val jmapClient: JmapClient) {
    /** Null when no digest was made yet, the server lacks the route, or it has the feature off. */
    suspend fun latest(): Digest? = read("$DIGEST_PATH/latest")

    /** Null when the server holds no digest by [id]. */
    suspend fun get(id: String): Digest? = read("$DIGEST_PATH/$id")

    private suspend fun read(path: String): Digest? {
        val body = jmapClient.webGetOrNull(path) ?: return null
        return body.decodeWeb(WireDigest.serializer(), "Digest") { it.toDigest() }
    }
}

private fun WireDigest.toDigest() =
    Digest(id, Instant.parse(from), Instant.parse(to), groups.map { DigestGroup(it.label, it.items.map(WireItem::toItem)) })

private fun WireItem.toItem() = DigestItem(threadId, emailId, from, subject, Instant.parse(receivedAt), summary)
