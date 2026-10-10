package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.JmapClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** A request the server holds open for a new device until a master approves it by [id] and [code]. */
data class DeviceRequest(val id: String, val code: String, val expiresAt: Instant)

/** A request still waiting for a master; the server keeps its code to the device that asked. */
data class PendingRequest(val id: String, val name: String, val expiresAt: Instant)

sealed interface PendingOutcome {
    class Listed(val requests: List<PendingRequest>) : PendingOutcome

    /** The server has device approval switched off, so nothing can be waiting. */
    data object NotServed : PendingOutcome
}

sealed interface OpenOutcome {
    class Opened(val request: DeviceRequest) : OpenOutcome

    data object TooManyOpen : OpenOutcome

    /** The server has device approval switched off, so no key can be asked for. */
    data object NotServed : OpenOutcome
}

enum class RequestStatus {
    Pending,
    Approved,
    Expired,

    /** The server switched device approval off while the request was open. */
    Off,
}

sealed interface ApproveOutcome {
    data object Approved : ApproveOutcome

    /** The master token proved nothing. */
    data object NotAccepted : ApproveOutcome

    /** The code was wrong, or the token is not the admin's. */
    data object Refused : ApproveOutcome

    /** The request is unknown, spent or past its lifetime. */
    data object Gone : ApproveOutcome

    /** The server could not make the key; the request is spent. */
    data object UpstreamFailed : ApproveOutcome
}

private const val MASTER_HEADER = "X-Corvid-Master"
private val APPROVE_STATUSES =
    setOf(
        HttpStatusCode.Unauthorized,
        HttpStatusCode.Forbidden,
        HttpStatusCode.Gone,
        HttpStatusCode.BadGateway,
    )

/** The device approval routes of the web tier: a new device asks, a master device answers. */
class DeviceApprovalRepository(private val jmapClient: JmapClient) {
    suspend fun open(deviceName: String): OpenOutcome {
        val reply =
            jmapClient.webSend(
                HttpMethod.Post,
                "$DEVICES_PATH/requests",
                buildJsonObject { put("name", deviceName) },
                passThrough = setOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable),
            )
        if (reply.status == HttpStatusCode.TooManyRequests) return OpenOutcome.TooManyOpen
        if (reply.approvalOff()) return OpenOutcome.NotServed
        val body = reply.body.orEmpty()
        return OpenOutcome.Opened(
            DeviceRequest(
                id = body.getValue("id").jsonPrimitive.content,
                code = body.getValue("code").jsonPrimitive.content,
                expiresAt = Instant.parse(body.getValue("expiresAt").jsonPrimitive.content),
            ),
        )
    }

    suspend fun pending(): PendingOutcome {
        val reply =
            jmapClient.webSend(HttpMethod.Get, "$DEVICES_PATH/requests", passThrough = setOf(HttpStatusCode.ServiceUnavailable))
        if (reply.approvalOff()) return PendingOutcome.NotServed
        val requests =
            reply.array.orEmpty().map { entry ->
                val fields = entry.jsonObject
                PendingRequest(
                    id = fields.getValue("id").jsonPrimitive.content,
                    name = fields.getValue("name").jsonPrimitive.content,
                    expiresAt = Instant.parse(fields.getValue("expiresAt").jsonPrimitive.content),
                )
            }
        return PendingOutcome.Listed(requests)
    }

    /** The sentinel answers any id it does not hold as expired, so a 404 never reached it and is thrown. */
    suspend fun status(id: String): RequestStatus {
        val reply =
            jmapClient.webSend(HttpMethod.Get, "$DEVICES_PATH/requests/$id", passThrough = setOf(HttpStatusCode.ServiceUnavailable))
        if (reply.approvalOff()) return RequestStatus.Off
        return when (reply.body?.get("status")?.jsonPrimitive?.content) {
            "pending" -> RequestStatus.Pending
            "approved" -> RequestStatus.Approved
            else -> RequestStatus.Expired
        }
    }

    /** [masterToken] rides one header on one request and is not kept. */
    suspend fun approve(
        id: String,
        code: String,
        masterToken: String,
    ): ApproveOutcome {
        val reply =
            jmapClient.webSend(
                HttpMethod.Post,
                "$DEVICES_PATH/requests/$id/approve",
                buildJsonObject { put("code", code) },
                headers = mapOf(MASTER_HEADER to masterToken),
                passThrough = APPROVE_STATUSES,
            )
        return when (reply.status) {
            HttpStatusCode.Unauthorized -> ApproveOutcome.NotAccepted
            HttpStatusCode.Forbidden -> ApproveOutcome.Refused
            HttpStatusCode.Gone -> ApproveOutcome.Gone
            HttpStatusCode.BadGateway -> ApproveOutcome.UpstreamFailed
            else -> ApproveOutcome.Approved
        }
    }
}
