package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.JmapJson
import com.verdenroz.corvid.core.model.Notice
import com.verdenroz.corvid.core.model.NoticeKind
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private const val NOTICES_PATH = "/corvid/notices"

@Serializable
private class WireNotices(val notices: List<WireNotice>)

@Serializable
private class WireNotice(
    val id: Long,
    val kind: NoticeKind,
    val at: String,
    val title: String,
    val detail: String,
)

/** The server's notices about itself, behind `/corvid/notices` on the web tier. */
class NoticeRepository(private val jmapClient: JmapClient) {
    /** Notices newer than [after], oldest first; null when the server does not offer the feed, as an older one does not. */
    suspend fun after(after: Long? = null): List<Notice>? {
        val reply = jmapClient.webGet(if (after == null) NOTICES_PATH else "$NOTICES_PATH?after=$after")
        val body = reply.body ?: return null
        return try {
            JmapJson.decodeFromJsonElement(WireNotices.serializer(), body).notices.map { it.toNotice() }
        } catch (e: IllegalArgumentException) {
            throw JmapException(JmapError.Request(type = "unrecognisedNotices", detail = e.message))
        }
    }
}

private fun WireNotice.toNotice() = Notice(id, kind, Instant.parse(at), title, detail)
