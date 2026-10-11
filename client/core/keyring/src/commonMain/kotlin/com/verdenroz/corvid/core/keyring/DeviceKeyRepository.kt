package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.WebReply
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

val DEVICE_NAME_PATTERN = Regex("[a-z0-9-]{1,32}")

internal const val DEVICES_PATH = "/corvid/devices"

/** A 404 is the sentinel's own answer for an unknown name; a 503 may mean approval is off. */
internal val DEVICES_PASS_THROUGH = setOf(HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)

/** True for the sentinel's answer on a host without a section the route needs; any other 503 is the server failing. */
internal fun WebReply.approvalOff(vararg off: String = arrayOf("devices_off")): Boolean {
    if (status != HttpStatusCode.ServiceUnavailable) return false
    if (body?.get("error")?.jsonPrimitive?.contentOrNull in off) return true
    throw JmapException(JmapError.Server(status.value))
}

private const val KEY_VERSION: Byte = 1
private const val KEY_LENGTH = 32
private const val KEY_CONTENT_TYPE = "application/octet-stream"

sealed interface DeviceKeyLookup {
    class Found(val key: ByteArray) : DeviceKeyLookup

    data object Missing : DeviceKeyLookup

    data object Damaged : DeviceKeyLookup

    /** The server has device approval switched off, so it holds no key for anyone. */
    data object ApprovalOff : DeviceKeyLookup

    class Unreachable(val error: JmapError) : DeviceKeyLookup
}

/**
 * Reads a device's key only by the record the server returns for it: the record names the
 * account and blob, and the key is that blob. A file is never looked up by name, since a file
 * planted under the same name would shadow the real one. Content is 1 version byte + 32 bytes;
 * any other size or leading byte is refused and left alone, since a damaged key must never
 * read as a missing one.
 */
class DeviceKeyRepository(private val jmapClient: JmapClient) {
    suspend fun fetch(deviceName: String): DeviceKeyLookup =
        try {
            val reply = jmapClient.webSend(HttpMethod.Get, "$DEVICES_PATH/$deviceName", passThrough = DEVICES_PASS_THROUGH)
            if (reply.approvalOff()) return DeviceKeyLookup.ApprovalOff
            val record = reply.body.takeIf { reply.status == HttpStatusCode.OK } ?: return DeviceKeyLookup.Missing
            val accountId = record["accountId"]?.jsonPrimitive?.contentOrNull
            val blobId = record["blobId"]?.jsonPrimitive?.contentOrNull
            if (accountId == null || blobId == null) {
                DeviceKeyLookup.Damaged
            } else {
                parseKey(jmapClient.downloadBlob(blobId, "$deviceName.key", KEY_CONTENT_TYPE, accountId))
            }
        } catch (e: JmapException) {
            DeviceKeyLookup.Unreachable(e.error)
        }

    private fun parseKey(bytes: ByteArray): DeviceKeyLookup =
        if (bytes.size == KEY_LENGTH + 1 && bytes[0] == KEY_VERSION) {
            val key = bytes.copyOfRange(1, bytes.size)
            bytes.fill(0)
            DeviceKeyLookup.Found(key)
        } else {
            DeviceKeyLookup.Damaged
        }
}
