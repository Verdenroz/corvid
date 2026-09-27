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
import kotlinx.serialization.json.JsonNull
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

    /** An RFC 7807 problem type (e.g. `urn:ietf:params:jmap:error:limit`) to answer every call with. */
    var apiProblem: String? = null

    /** Answers a call with a body that isn't JMAP at all, as a misconfigured proxy might. */
    var malformedApiResponse: Boolean = false

    /** Drops one callId from the response, as a buggy server might. */
    var dropCallId: String? = null

    /** Omits the session's `state`, to exercise adopting a call's sessionState instead of refetching. */
    var omitSessionState: Boolean = false

    val mailboxes: MutableList<FakeMailbox> = mutableListOf()
    val emails: MutableList<FakeEmail> = mutableListOf()
    val fileNodes: MutableList<FakeFileNode> = mutableListOf()
    val blobs: MutableMap<String, ByteArray> = mutableMapOf()
    private var nextFileNodeId = 1
    private var nextBlobId = 1

    /** FileNode ids that fail `destroy` with `notDestroyed`, as a still-referenced node would. */
    val refuseDestroy: MutableSet<String> = mutableSetOf()

    /** Fails every FileNode `create` with `notCreated`, as a quota or permission refusal would. */
    var refuseCreate: Boolean = false

    var requestCount: Int = 0
        private set

    val engine: MockEngine = MockEngine { request -> handle(request) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        requestCount++
        if (unreachable) throw IOException("simulated network failure")
        if (tlsFailure) throw SSLHandshakeException("simulated certificate failure")
        val path = request.url.encodedPath
        return when {
            path.endsWith("/jmap/session") -> sessionResponse()
            path.endsWith("/jmap/api/") -> apiResponse(request)
            path.startsWith("/jmap/upload/") -> uploadResponse(request)
            path.startsWith("/jmap/download/") -> downloadResponse(path)
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.uploadResponse(request: HttpRequestData): HttpResponseData {
        val content = request.body as OutgoingContent.ByteArrayContent
        val bytes = content.bytes()
        val blobId = "blob${nextBlobId++}"
        blobs[blobId] = bytes
        val body =
            buildJsonObject {
                put("accountId", ACCOUNT_ID)
                put("blobId", blobId)
                put("type", content.contentType?.toString() ?: "application/octet-stream")
                put("size", bytes.size)
            }
        return respond(body.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, listOf("application/json")))
    }

    private fun MockRequestHandleScope.downloadResponse(path: String): HttpResponseData {
        val blobId = path.removePrefix("/jmap/download/").split("/").getOrNull(1)
        val bytes = blobs[blobId] ?: return respond("blob not found", HttpStatusCode.NotFound)
        return respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, listOf("application/octet-stream")))
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
        apiProblem?.let { type ->
            val problem =
                buildJsonObject {
                    put("type", type)
                    put("detail", "simulated problem")
                }
            return respond(
                problem.toString(),
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, listOf("application/problem+json")),
            )
        }
        if (malformedApiResponse) {
            return respond("<html>not jmap</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, listOf("text/html")))
        }

        val requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val calls =
            JmapJson.parseToJsonElement(requestBody).jsonObject
                .getValue("methodCalls").jsonArray
                .map { JmapJson.decodeFromJsonElement<Invocation>(it) }

        val results = mutableMapOf<String, JsonObject>()
        val responses =
            calls
                .map { invocation ->
                    val (name, body) = dispatch(invocation.name, resolveReferences(invocation.arguments, results))
                    results[invocation.callId] = body
                    Invocation(name, body, invocation.callId)
                }
                .filter { it.callId != dropCallId }

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
            "FileNode/query" -> fileNodeQuery(arguments)
            "FileNode/get" -> "FileNode/get" to fileNodeGet(arguments)
            "FileNode/set" -> "FileNode/set" to fileNodeSet(arguments)
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

    /** Mirrors a real quirk: Stalwart rejects `parentId: null` in a filter rather than treating it as "root". */
    private fun fileNodeQuery(arguments: JsonObject): Pair<String, JsonObject> {
        val filter = arguments["filter"]?.jsonObject ?: JsonObject(emptyMap())
        if (filter["parentId"] is JsonNull) {
            return "error" to problem("invalidArguments", "invalid type: null, expected a borrowed string")
        }
        val parentId = filter["parentId"]?.jsonPrimitive?.contentOrNull
        val name = filter["name"]?.jsonPrimitive?.contentOrNull
        val matching =
            fileNodes.filter { node ->
                (parentId == null || node.parentId == parentId) && (name == null || node.name == name)
            }
        return "FileNode/query" to
            buildJsonObject {
                put("accountId", ACCOUNT_ID)
                put("queryState", "fs1")
                put("canCalculateChanges", true)
                put("position", 0)
                put("ids", JsonArray(matching.map { JsonPrimitive(it.id) }))
            }
    }

    private fun fileNodeGet(arguments: JsonObject): JsonObject {
        val ids = arguments["ids"]?.jsonArray?.map { it.jsonPrimitive.content }
        val properties = arguments["properties"]?.jsonArray?.map { it.jsonPrimitive.content }
        val selected = if (ids == null) fileNodes else ids.mapNotNull { id -> fileNodes.find { it.id == id } }
        val notFound = ids.orEmpty().filter { id -> fileNodes.none { it.id == id } }
        return buildJsonObject {
            put("accountId", ACCOUNT_ID)
            put("state", "fs1")
            put("list", JsonArray(selected.map { it.toJson(properties) }))
            put("notFound", JsonArray(notFound.map { JsonPrimitive(it) }))
        }
    }

    private fun fileNodeSet(arguments: JsonObject): JsonObject {
        val created = mutableMapOf<String, JsonObject>()
        val notCreated = mutableMapOf<String, JsonObject>()
        arguments["create"]?.jsonObject?.forEach { (creationId, spec) ->
            if (refuseCreate) {
                notCreated[creationId] = problem("forbidden", "creation refused")
                return@forEach
            }
            val specObj = spec.jsonObject
            val name = specObj.getValue("name").jsonPrimitive.content
            val parentId = specObj["parentId"]?.jsonPrimitive?.contentOrNull
            val blobId = specObj["blobId"]?.jsonPrimitive?.contentOrNull
            val id = "f${nextFileNodeId++}"
            fileNodes += FakeFileNode(id, parentId, name, if (blobId != null) "file" else "directory", blobId)
            created[creationId] = buildJsonObject { put("id", id) }
        }
        val destroyed = mutableListOf<String>()
        val notDestroyed = mutableMapOf<String, JsonObject>()
        arguments["destroy"]?.jsonArray?.map { it.jsonPrimitive.content }?.forEach { id ->
            when {
                id in refuseDestroy -> notDestroyed[id] = problem("forbidden", "destroy refused")
                fileNodes.removeAll { it.id == id } -> destroyed += id
                else -> notDestroyed[id] = problem("notFound", "no such node")
            }
        }
        return buildJsonObject {
            put("accountId", ACCOUNT_ID)
            put("oldState", "fs0")
            put("newState", "fs1")
            if (created.isNotEmpty()) putJsonObject("created") { created.forEach { (k, v) -> put(k, v) } }
            if (notCreated.isNotEmpty()) putJsonObject("notCreated") { notCreated.forEach { (k, v) -> put(k, v) } }
            if (destroyed.isNotEmpty()) put("destroyed", JsonArray(destroyed.map { JsonPrimitive(it) }))
            if (notDestroyed.isNotEmpty()) putJsonObject("notDestroyed") { notDestroyed.forEach { (k, v) -> put(k, v) } }
        }
    }

    private fun problem(
        type: String,
        description: String,
    ) = buildJsonObject {
        put("type", type)
        put("description", description)
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
            if (!omitSessionState) put("state", "s1")
        }
}

private fun FakeFileNode.toJson(properties: List<String>?): JsonObject {
    val full =
        buildJsonObject {
            put("id", id)
            put("parentId", parentId)
            put("nodeType", nodeType)
            put("blobId", blobId)
            put("name", name)
        }
    return if (properties == null) full else JsonObject(full.filterKeys { it in properties || it == "id" })
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
