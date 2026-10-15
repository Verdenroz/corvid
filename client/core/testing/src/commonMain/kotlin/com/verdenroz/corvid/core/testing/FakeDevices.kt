package com.verdenroz.corvid.core.testing

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

    /** Answers the push routes 503 `push_off`, as a sentinel without `sentinel.push` does. */
    var pushOff: Boolean = false

    /** Answers a push endpoint not under this prefix 422, as the sentinel does one that is not FCM's. */
    var pushBase: String = "https://fcm.googleapis.com/"

    /** What the first registration of an own connection is answered with; registering the same one again answers 204. */
    var streamToken: String = "stream-token"

    /** What `/corvid/push/vapid` publishes. */
    var vapidKey: String = "B" + "x".repeat(86)

    /** The push endpoint each device stored, as the body it sent. */
    val pushEndpoints: MutableMap<String, JsonObject> = mutableMapOf()

    /** Each push route call as method and device name, in order. */
    val pushCalls: MutableList<Pair<HttpMethod, String>> = mutableListOf()

    /** Answers the push kinds route 404, as a server from before it does. */
    var kindsUnsupported: Boolean = false

    /** Answers the push kinds route 500. */
    var kindsBroken: Boolean = false

    /** The push kinds each device asked for. */
    val pushKinds: MutableMap<String, List<String>> = mutableMapOf()

    /** The number of push kinds requests, whatever they were answered. */
    var kindsCalls: Int = 0
        private set

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
            segments == listOf("requests") && request.method == HttpMethod.Get -> pending(scope)
            segments.size == 2 && segments[0] == "requests" && request.method == HttpMethod.Get -> status(scope, segments[1])
            segments.size == 3 && segments[0] == "requests" && segments[2] == "approve" && request.method == HttpMethod.Post ->
                approve(scope, request, segments[1])
            segments.size == 1 && request.method == HttpMethod.Get -> record(scope, segments[0])
            segments.size == 3 && segments[1] == "push" && segments[2] == "kinds" && request.method == HttpMethod.Put ->
                kinds(scope, request, segments[0])
            segments.size == 2 && segments[1] == "push" -> push(scope, request, segments[0])
            else -> scope.respond("not found", HttpStatusCode.NotFound)
        }
    }

    internal fun vapid(scope: MockRequestHandleScope): HttpResponseData =
        if (pushOff) {
            json(scope, HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", "push_off") })
        } else {
            json(scope, HttpStatusCode.OK, buildJsonObject { put("publicKey", vapidKey) })
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

    private fun pending(scope: MockRequestHandleScope): HttpResponseData {
        val waiting =
            requests.entries.reversed().filter { it.value.status == "pending" }.map { (id, request) ->
                buildJsonObject {
                    put("id", id)
                    put("name", request.name)
                    put("expiresAt", expiresAt)
                }
            }
        return scope.respond(
            JsonArray(waiting).toString(),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, listOf("application/json")),
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

    private fun kinds(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
        name: String,
    ): HttpResponseData {
        kindsCalls++
        if (kindsBroken) return scope.respond("", HttpStatusCode.InternalServerError)
        if (kindsUnsupported || name !in records) return scope.respond("", HttpStatusCode.NotFound)
        pushKinds[name] = body(request).getValue("kinds").jsonArray.map { it.jsonPrimitive.content }
        return scope.respond("", HttpStatusCode.NoContent)
    }

    private fun push(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
        name: String,
    ): HttpResponseData {
        pushCalls += request.method to name
        if (pushOff) return json(scope, HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", "push_off") })
        if (name !in records) return scope.respond("", HttpStatusCode.NotFound)
        return when (request.method) {
            HttpMethod.Put -> {
                val sent = body(request)
                val endpoint = sent.getValue("endpoint").jsonPrimitive.content
                when {
                    endpoint == "corvid-stream" && pushEndpoints[name] == sent -> scope.respond("", HttpStatusCode.NoContent)
                    endpoint == "corvid-stream" -> {
                        pushEndpoints[name] = sent
                        json(scope, HttpStatusCode.OK, buildJsonObject { put("token", streamToken) })
                    }
                    !endpoint.startsWith(pushBase) -> scope.respond("", HttpStatusCode.UnprocessableEntity)
                    else -> {
                        pushEndpoints[name] = sent
                        scope.respond("", HttpStatusCode.NoContent)
                    }
                }
            }
            HttpMethod.Delete -> {
                pushEndpoints.remove(name)
                scope.respond("", HttpStatusCode.NoContent)
            }
            else -> scope.respond("not found", HttpStatusCode.NotFound)
        }
    }
}
