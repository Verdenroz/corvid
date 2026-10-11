package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.WebReply
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class PushAnswer {
    Accepted,

    /** The sentinel has no `push` section, or no `devices` section. */
    OffOnServer,

    /** The server knows no approved device by that name. */
    NotApproved,

    /** The endpoint is not one of Google's. */
    Refused,
}

/** [token] is what the server issued for reading an own connection; Google endpoints have none. */
data class PushResult(val answer: PushAnswer, val token: String? = null)

private const val VAPID_PATH = "/corvid/push/vapid"
private val OFF_ERRORS = setOf("push_off", "devices_off")
private val PUSH_PASS_THROUGH = DEVICES_PASS_THROUGH + HttpStatusCode.UnprocessableEntity

/** Where the server pushes this device's new-mail previews: `/corvid/devices/<name>/push`. */
class PushEndpointRepository(private val jmapClient: JmapClient) {
    /** [p256dh] and [auth] are base64url, as the connector gives them. */
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
        return PushResult(reply.answer(), reply.body?.get("token")?.jsonPrimitive?.contentOrNull)
    }

    /** The key a push to this server must be signed with, or null where the server pushes nothing. */
    suspend fun vapidKey(): String? {
        val reply = jmapClient.webSend(HttpMethod.Get, VAPID_PATH, passThrough = DEVICES_PASS_THROUGH)
        return when {
            reply.status == HttpStatusCode.OK -> reply.body?.get("publicKey")?.jsonPrimitive?.contentOrNull
            reply.status == HttpStatusCode.NotFound || reply.isOff() -> null
            else -> throw JmapException(JmapError.Server(reply.status.value))
        }
    }

    /** Succeeds also when the server held no endpoint for [deviceName]. */
    suspend fun remove(deviceName: String): PushAnswer =
        jmapClient.webSend(HttpMethod.Delete, path(deviceName), passThrough = PUSH_PASS_THROUGH).answer()

    private fun path(deviceName: String) = "$DEVICES_PATH/$deviceName/push"

    /** Any 503 but the sentinel's own is the server failing. */
    private fun WebReply.answer(): PushAnswer =
        when {
            status == HttpStatusCode.NotFound -> PushAnswer.NotApproved
            status == HttpStatusCode.UnprocessableEntity -> PushAnswer.Refused
            isOff() -> PushAnswer.OffOnServer
            status == HttpStatusCode.ServiceUnavailable -> throw JmapException(JmapError.Server(status.value))
            else -> PushAnswer.Accepted
        }

    private fun WebReply.isOff() =
        status == HttpStatusCode.ServiceUnavailable && body?.get("error")?.jsonPrimitive?.contentOrNull in OFF_ERRORS
}
