package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.WebReply
import com.verdenroz.corvid.core.prefs.PushStatus
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the server made of a registration: [status] as it is recorded, and [token] for an own connection. */
data class PushResult(val status: PushStatus, val token: String? = null)

private const val VAPID_PATH = "/corvid/push/vapid"
private val PUSH_OFF = arrayOf("push_off", "devices_off")
private val PUSH_PASS_THROUGH = DEVICES_PASS_THROUGH + HttpStatusCode.UnprocessableEntity

/** Where the server pushes this device's new-mail previews: `/corvid/devices/<name>/push`. */
class PushEndpointRepository(private val jmapClient: JmapClient) {
    /**
     * [p256dh] and [auth] are base64url, as the connector gives them. The token comes only with the first
     * registration of an own connection; registering the same one again answers without it.
     */
    suspend fun store(
        deviceName: String,
        endpoint: String,
        p256dh: String,
        auth: String,
    ): PushResult {
        val body =
            buildJsonObject {
                put("endpoint", endpoint)
                put("p256dh", p256dh)
                put("auth", auth)
            }
        val reply = jmapClient.webSend(HttpMethod.Put, path(deviceName), body, passThrough = PUSH_PASS_THROUGH)
        return PushResult(reply.status(), reply.body?.get("token")?.jsonPrimitive?.contentOrNull)
    }

    /** The key a push to this server must be signed with, or null where the server pushes nothing. */
    suspend fun vapidKey(): String? {
        val reply = jmapClient.webSend(HttpMethod.Get, VAPID_PATH, passThrough = DEVICES_PASS_THROUGH)
        if (reply.status == HttpStatusCode.NotFound || reply.approvalOff(*PUSH_OFF)) return null
        return reply.body?.get("publicKey")?.jsonPrimitive?.contentOrNull
    }

    /** Succeeds also when the server held no endpoint for [deviceName], or pushes nothing. */
    suspend fun remove(deviceName: String) {
        jmapClient.webSend(HttpMethod.Delete, path(deviceName), passThrough = PUSH_PASS_THROUGH).approvalOff(*PUSH_OFF)
    }

    private fun path(deviceName: String) = "$DEVICES_PATH/$deviceName/push"

    private fun WebReply.status(): PushStatus =
        when {
            status == HttpStatusCode.NotFound -> PushStatus.NotApproved
            status == HttpStatusCode.UnprocessableEntity -> PushStatus.Refused
            approvalOff(*PUSH_OFF) -> PushStatus.OffOnServer
            else -> PushStatus.Sent
        }
}
