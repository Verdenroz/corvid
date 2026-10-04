package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.JmapJson
import com.verdenroz.corvid.core.model.UpdateReport
import com.verdenroz.corvid.core.model.UpdateResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

private const val UPDATE_PATH = "/corvid/update"

@Serializable
private class WireReport(
    val running: String,
    val latest: String? = null,
    val checkedAt: String? = null,
    val result: UpdateResult,
    val detail: String? = null,
    val auto: Boolean,
    val busy: Boolean,
)

/** The server's own release state, behind `/corvid/update` on the web tier. */
class UpdateRepository(private val jmapClient: JmapClient) {
    /** Null when the server does not offer the endpoint, as an older one does not. */
    suspend fun status(): UpdateReport? {
        val reply = jmapClient.webGet(UPDATE_PATH)
        val body = reply.body ?: return null
        return try {
            JmapJson.decodeFromJsonElement(WireReport.serializer(), body).toReport()
        } catch (e: IllegalArgumentException) {
            throw JmapException(JmapError.Request(type = "unrecognisedUpdateReport", detail = e.message))
        }
    }

    suspend fun check() = post("check")

    suspend fun apply() = post("apply")

    private suspend fun post(action: String) {
        jmapClient.webPost(UPDATE_PATH, buildJsonObject { put("action", action) })
    }
}

private fun WireReport.toReport() =
    UpdateReport(
        running = running,
        latest = latest,
        checkedAt = checkedAt?.let(Instant::parse),
        result = result,
        detail = detail,
        auto = auto,
        busy = busy,
    )
