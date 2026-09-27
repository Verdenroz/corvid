package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

private const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
private const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
private const val FILENODE_CAPABILITY = "urn:ietf:params:jmap:filenode"
private const val ACCOUNT_ID = "u1"

/**
 * Interprets JMAP against an in-memory mailbox, advertising
 * `https://mail.corvid.test/jmap/...` with no port, the way a real Stalwart
 * deployment does before the caller rebases it. FileNode/blob endpoints
 * join in slice 5.
 */
class FakeJmapServer {
    var unauthorized: Boolean = false
    var unreachable: Boolean = false
    var tlsFailure: Boolean = false

    /** Seconds sent back in `Retry-After`; non-null also switches the response to 429. */
    var rateLimitedFor: Long? = null

    /** No token presented: capabilities only, no account (decision 5's anonymous session). */
    var unauthenticated: Boolean = false

    /** Drops one capability from the advertised session, to exercise the required-capability check. */
    var missingCapability: String? = null

    /** Drops one URL field from the advertised session, to exercise the required-endpoint check. */
    var missingUrl: String? = null

    /** Bumped by a test to simulate a mailbox change invalidating an open query. */
    var queryState: String = "qs1"

    val mailboxes: MutableList<FakeMailbox> = mutableListOf()
    val emails: MutableList<FakeEmail> = mutableListOf()

    var requestCount: Int = 0
        private set

    val engine: MockEngine = MockEngine { request -> handle(request) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        requestCount++
        if (unreachable) throw IOException("simulated network failure")
        if (tlsFailure) throw SSLHandshakeException("simulated certificate failure")
        return when {
            request.url.encodedPath.endsWith("/jmap/session") -> sessionResponse()
            request.url.encodedPath.endsWith("/jmap/api/") -> apiResponse(request)
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.sessionResponse(): HttpResponseData {
        if (unauthorized) return respond("", HttpStatusCode.Unauthorized)
        rateLimitedFor?.let { seconds ->
            return respond(
                "",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, listOf(seconds.toString())),
            )
        }
        return respond(
            sessionBody().toString(),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, listOf("application/json")),
        )
    }

    private suspend fun MockRequestHandleScope.apiResponse(request: HttpRequestData): HttpResponseData {
        val requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val calls =
            JmapJson.parseToJsonElement(requestBody).jsonObject
                .getValue("methodCalls").jsonArray
                .map { JmapJson.decodeFromJsonElement<Invocation>(it) }

        val results = mutableMapOf<String, JsonObject>()
        val responses =
            calls.map { invocation ->
                val (name, body) = dispatch(invocation.name, resolveReferences(invocation.arguments, results))
                results[invocation.callId] = body
                Invocation(name, body, invocation.callId)
            }

        val responseBody =
            buildJsonObject {
                put("methodResponses", JsonArray(responses.map { JmapJson.encodeToJsonElement(it) }))
                put("sessionState", "s1")
            }
        return respond(
            responseBody.toString(),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, listOf("application/json")),
        )
    }

    private fun dispatch(
        name: String,
        arguments: JsonObject,
    ): Pair<String, JsonObject> =
        when (name) {
            "Mailbox/get" -> "Mailbox/get" to mailboxGet(arguments)
            "Email/query" -> emailQuery(arguments)
            "Email/get" -> "Email/get" to emailGet(arguments)
            else -> "error" to buildJsonObject { put("type", "unknownMethod") }
        }

    private fun mailboxGet(arguments: JsonObject): JsonObject {
        val ids = arguments["ids"]?.jsonArray?.map { it.jsonPrimitive.content }
        val selected = if (ids == null) mailboxes else ids.mapNotNull { id -> mailboxes.find { it.id == id } }
        val notFound = ids.orEmpty().filter { id -> mailboxes.none { it.id == id } }
        return buildJsonObject {
            put("accountId", ACCOUNT_ID)
            put("state", "s1")
            put("list", JsonArray(selected.map { it.toJson() }))
            put("notFound", JsonArray(notFound.map { JsonPrimitive(it) }))
        }
    }

    private fun emailQuery(arguments: JsonObject): Pair<String, JsonObject> {
        val inMailbox = arguments["filter"]?.jsonObject?.get("inMailbox")?.jsonPrimitive?.contentOrNull
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val anchor = arguments["anchor"]?.jsonPrimitive?.contentOrNull
        val anchorOffset = arguments["anchorOffset"]?.jsonPrimitive?.intOrNull ?: 0
        val position = arguments["position"]?.jsonPrimitive?.intOrNull ?: 0

        val matching =
            emails
                .filter { inMailbox == null || inMailbox in it.mailboxIds }
                .sortedByDescending { it.receivedAt }

        val start =
            if (anchor != null) {
                val anchorIndex = matching.indexOfFirst { it.id == anchor }
                if (anchorIndex < 0) return "error" to buildJsonObject { put("type", "anchorNotFound") }
                (anchorIndex + anchorOffset).coerceIn(0, matching.size)
            } else {
                position.coerceIn(0, matching.size)
            }
        val page = matching.drop(start).take(limit)

        return "Email/query" to
            buildJsonObject {
                put("accountId", ACCOUNT_ID)
                put("queryState", queryState)
                put("canCalculateChanges", false)
                put("position", start)
                put("total", matching.size)
                put("ids", JsonArray(page.map { JsonPrimitive(it.id) }))
            }
    }

    private fun emailGet(arguments: JsonObject): JsonObject {
        val ids = arguments["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val found = ids.mapNotNull { id -> emails.find { it.id == id } }
        val notFound = ids.filter { id -> emails.none { it.id == id } }
        return buildJsonObject {
            put("accountId", ACCOUNT_ID)
            put("state", "s1")
            put("list", JsonArray(found.map { it.toJson() }))
            put("notFound", JsonArray(notFound.map { JsonPrimitive(it) }))
        }
    }

    private fun resolveReferences(
        arguments: JsonObject,
        results: Map<String, JsonObject>,
    ): JsonObject =
        buildJsonObject {
            for ((key, value) in arguments) {
                if (key.startsWith("#")) {
                    val reference = value.jsonObject
                    val resultOf = reference.getValue("resultOf").jsonPrimitive.content
                    val path = reference.getValue("path").jsonPrimitive.content
                    val priorResult = results[resultOf] ?: JsonObject(emptyMap())
                    put(key.removePrefix("#"), resolveJsonPointer(priorResult, path))
                } else {
                    put(key, value)
                }
            }
        }

    private fun sessionBody(): JsonObject =
        buildJsonObject {
            putJsonObject("capabilities") {
                for (capability in listOf(CORE_CAPABILITY, MAIL_CAPABILITY, FILENODE_CAPABILITY)) {
                    if (capability != missingCapability) putJsonObject(capability) {}
                }
            }
            if (unauthenticated) {
                putJsonObject("accounts") {}
                putJsonObject("primaryAccounts") {}
                put("username", "")
            } else {
                putJsonObject("accounts") { putJsonObject(ACCOUNT_ID) { put("name", "verde") } }
                putJsonObject("primaryAccounts") { put(MAIL_CAPABILITY, ACCOUNT_ID) }
                put("username", "verde")
            }
            for (
            (key, value) in
            mapOf(
                "apiUrl" to "https://mail.corvid.test/jmap/api/",
                "downloadUrl" to "https://mail.corvid.test/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
                "uploadUrl" to "https://mail.corvid.test/jmap/upload/{accountId}/",
                "eventSourceUrl" to "https://mail.corvid.test/jmap/eventsource/",
            )
            ) {
                if (key != missingUrl) put(key, value)
            }
            put("state", "s1")
        }
}

private fun FakeMailbox.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("role", role)
        put("parentId", parentId)
        put("totalEmails", totalEmails)
        put("unreadEmails", unreadEmails)
    }

private fun FakeEmail.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("threadId", threadId)
        put("subject", subject)
        put("from", from)
        put("receivedAt", receivedAt)
        putJsonObject("keywords") { for (keyword in keywords) put(keyword, true) }
        put("preview", preview)
    }
