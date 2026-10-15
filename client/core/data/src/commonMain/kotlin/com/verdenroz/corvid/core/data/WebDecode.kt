package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.JmapJson
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

internal val ABSENT = setOf(HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)

/** The body of a GET on [path], or null when the server lacks the route or has the feature off. */
internal suspend fun JmapClient.webGetOrNull(path: String): JsonObject? {
    val reply = webSend(HttpMethod.Get, path, passThrough = ABSENT)
    return reply.body?.takeIf { reply.status == HttpStatusCode.OK }
}

internal fun <T, R> JsonObject.decodeWeb(
    serializer: KSerializer<T>,
    what: String,
    transform: (T) -> R,
): R =
    try {
        transform(JmapJson.decodeFromJsonElement(serializer, this))
    } catch (e: IllegalArgumentException) {
        throw JmapException(JmapError.Request(type = "unrecognised$what", detail = e.message))
    }
