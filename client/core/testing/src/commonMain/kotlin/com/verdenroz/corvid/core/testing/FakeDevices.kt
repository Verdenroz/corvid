package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.JmapJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** One approval attempt as the server saw it: the master header and the daily bearer. */
data class FakeApproval(
    val id: String,
    val code: String,
    val master: String?,
    val authorization: String?,
)

/** `/corvid/devices`: requests a new device opens, approval by the master, and the record each approved key leaves. */
class FakeDevices(private val blobs: MutableMap<String, ByteArray>) {
    /** The account the keys live in, which the session lists beside the mailbox account. */
    val keysAccountId = "k1"

    /** The only `X-Corvid-Master` value an approval accepts. */
    var masterToken: String = "master-token"

    /** Answers a request to open with 429, as a server with too many open does. */
    var full: Boolean = false

    /** Answers every device route 503 `devices_off`, as a sentinel without `sentinel.devices` does. */
    var off: Boolean = false

    /** Answers every device route 404, as Stalwart does for a request Caddy did not route to the sentinel. */
    var unrouted: Boolean = false

    /** Answers an approval that passes every check with 502, as a failed key creation does. */
    var upstreamFails: Boolean = false

    /** Refuses every request the way the forward-auth gate refuses a daily bearer Stalwart does not accept. */
    var dailyRejected: Boolean = false

    var expiresAt: String = "2030-01-01T00:00:00Z"

    /** Ids of blobs that live in the keys account and download from no other. */
    val keyBlobIds: MutableSet<String> = mutableSetOf()

    val opened: MutableList<String> = mutableListOf()
    val approvals: MutableList<FakeApproval> = mutableListOf()

    private class Request(val name: String, val code: String, var status: String)

    private val requests = mutableMapOf<String, Request>()
    private val records = mutableMapOf<String, JsonObject>()
    private var nextRequest = 1
    private var nextKeyBlob = 1

    /** The code shown on the new device for [id]. */
    fun codeOf(id: String): String = requests.getValue(id).code

    /** Ends [id] as a lifetime running out does; the server then calls it expired. */
    fun expire(id: String) {
        requests.getValue(id).status = "expired"
    }

    /** Makes the key for [name] as an approval does and returns its bytes; replaces any earlier record. */
    fun enroll(name: String): ByteArray {
        val key = Random.nextBytes(32)
        val blobId = "key${nextKeyBlob++}"
        blobs[blobId] = byteArrayOf(1) + key
        keyBlobIds += blobId
        records[name] =
            buildJsonObject {
                put("accountId", keysAccountId)
                put("nodeId", "n$blobId")
                put("blobId", blobId)
            }
        return key
    }

    fun revoke(name: String) {
        records.remove(name)
    }

    fun blobIdOf(name: String): String = records.getValue(name).getValue("blobId").jsonPrimitive.content

    /** Approves [id] as a master on another device would. */
    fun approveElsewhere(id: String) {
        val request = requests.getValue(id)
        enroll(request.name)
        request.status = "approved"
    }

    internal fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        if (dailyRejected) {
            return scope.respond(
                "",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.WWWAuthenticate, listOf("Bearer realm=\"Stalwart Server\"")),
            )
        }
        if (unrouted) return scope.respond("not found", HttpStatusCode.NotFound)
        if (off) return json(scope, HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", "devices_off") })
        val segments = request.url.encodedPath.removePrefix("/corvid/devices").trim('/').split("/").filter { it.isNotEmpty() }
        return when {
            segments == listOf("requests") && request.method == HttpMethod.Post -> open(scope, request)
            segments.size == 2 && segments[0] == "requests" && request.method == HttpMethod.Get -> status(scope, segments[1])
            segments.size == 3 && segments[0] == "requests" && segments[2] == "approve" && request.method == HttpMethod.Post ->
                approve(scope, request, segments[1])
            segments.size == 1 && request.method == HttpMethod.Get -> record(scope, segments[0])
            else -> scope.respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun open(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        if (full) return scope.respond("", HttpStatusCode.TooManyRequests)
        val name = body(request).getValue("name").jsonPrimitive.content
        val id = "req${nextRequest++}"
        val code = Random.nextInt(1_000_000).toString().padStart(6, '0')
        requests[id] = Request(name, code, "pending")
        opened += name
        return json(
            scope,
            HttpStatusCode.Created,
            buildJsonObject {
                put("id", id)
                put("code", code)
                put("expiresAt", expiresAt)
            },
        )
    }

    private fun status(
        scope: MockRequestHandleScope,
        id: String,
    ): HttpResponseData = json(scope, HttpStatusCode.OK, buildJsonObject { put("status", requests[id]?.status ?: "expired") })

    private fun approve(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
        id: String,
    ): HttpResponseData {
        val code = body(request).getValue("code").jsonPrimitive.content
        val master = request.headers["X-Corvid-Master"]
        approvals += FakeApproval(id, code, master, request.headers[HttpHeaders.Authorization])
        val pending = requests[id]?.takeIf { it.status == "pending" } ?: return scope.respond("", HttpStatusCode.Gone)
        return when {
            master != masterToken -> scope.respond("", HttpStatusCode.Unauthorized)
            code != pending.code -> scope.respond("", HttpStatusCode.Forbidden)
            upstreamFails -> {
                pending.status = "expired"
                scope.respond("", HttpStatusCode.BadGateway)
            }
            else -> {
                approveElsewhere(id)
                json(scope, HttpStatusCode.OK, buildJsonObject {})
            }
        }
    }

    private fun record(
        scope: MockRequestHandleScope,
        name: String,
    ): HttpResponseData = records[name]?.let { json(scope, HttpStatusCode.OK, it) } ?: scope.respond("not found", HttpStatusCode.NotFound)

    private fun body(request: HttpRequestData): JsonObject =
        JmapJson.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()) as JsonObject

    private fun json(
        scope: MockRequestHandleScope,
        status: HttpStatusCode,
        body: JsonObject,
    ): HttpResponseData = scope.respond(body.toString(), status, headersOf(HttpHeaders.ContentType, listOf("application/json")))
}
