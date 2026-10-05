package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.JmapJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

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
