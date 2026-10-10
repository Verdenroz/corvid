package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapJson
import com.verdenroz.corvid.core.jmap.MAIL_CAPABILITY
import com.verdenroz.corvid.core.jmap.REQUIRED_CAPABILITIES
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

private const val ACCOUNT_ID = "u1"

/**
 * Interprets JMAP against an in-memory mailbox, advertising
 * `https://mail.corvid.test/jmap/...` with no port, the way a real Stalwart
 * deployment does before the caller rebases it. [dispatcher] runs the engine's
 * handler: a test that polls on virtual time passes its own, so no answer
 * lands from another thread.
 */
class FakeJmapServer(private val dispatcher: CoroutineDispatcher? = null) {
    var unauthorized: Boolean = false
    var unreachable: Boolean = false
    var tlsFailure: Boolean = false

    /** Seconds sent back in `Retry-After`; non-null also switches the response to 429. */
    var rateLimitedFor: Long? = null

    /** No token presented: capabilities only, no account, as an anonymous session answers. */
    var unauthenticated: Boolean = false

    /** Drops one capability from the advertised session, to exercise the required-capability check. */
    var missingCapability: String? = null

    /** Drops one URL field from the advertised session, to exercise the required-endpoint check. */
    var missingUrl: String? = null

    /** Bumped by a test to simulate a mailbox change invalidating an open query. */
    var queryState: String = "qs1"

    /** Runs before each Email/query is answered, so a test can change the mailbox between pages. */
    var beforeEmailQuery: () -> Unit = {}

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
    val identities: MutableList<FakeIdentity> = mutableListOf()
    val submissions: MutableList<FakeSubmission> = mutableListOf()

    /** Every Email/set update patch received, in order, as the client sent it. */
    val emailPatches: MutableList<Pair<String, JsonObject>> = mutableListOf()
    val fileNodes: MutableList<FakeFileNode> = mutableListOf()
    val sieveScripts: MutableList<FakeSieveScript> = mutableListOf()
    val blobs: MutableMap<String, ByteArray> = mutableMapOf()

    /** `/corvid/devices`, the sentinel's device approval, and the keys account it fills. */
    val devices = FakeDevices(blobs)

    /** Bodies served under `/i/`, as the image proxy serves them, keyed by the path after it. */
    val proxiedImages: MutableMap<String, ByteArray> = mutableMapOf()

    /** The Authorization header of each `/i/` request, null where there was none. */
    val imageAuthorizations: MutableList<String?> = mutableListOf()
    private var nextFileNodeId = 1
    private var nextSieveScriptId = 1
    private var nextBlobId = 1
    private var nextEmailId = 1
    private var nextSubmissionId = 1

    /** FileNode ids that fail `destroy` with `notDestroyed`, as a still-referenced node would. */
    val refuseDestroy: MutableSet<String> = mutableSetOf()

    /** Fails every FileNode `create` with `notCreated`, as a quota or permission refusal would. */
    var refuseCreate: Boolean = false

    /** Fails every SieveScript `create` and `update` with `invalidSieve`, as a script the server cannot compile would. */
    var invalidSieve: Boolean = false

    /** Fails every Email `create` with `notCreated`, as an over-quota account would. */
    var refuseEmailCreate: Boolean = false

    /** Fails every EmailSubmission `create` with `notCreated`, as a rejected sender or recipient would. */
    var refuseSubmission: Boolean = false

    /** While set, a JMAP API request waits on it before it is answered, as a slow server would. */
    var gate: CompletableDeferred<Unit>? = null

    // Handlers run on the engine's background threads; one request at a time, as a server applies them.
    private val state = Mutex()

    /** `/corvid/update`, the server's release report. */
    val update = FakeWebEndpoint()

    /** `/corvid/notices`, the sentinel's feed. */
    val notices = FakeWebEndpoint()

    private val webTier = mapOf("/corvid/update" to update, "/corvid/notices" to notices)

    var requestCount: Int = 0
        private set

    val engine: MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                this@FakeJmapServer.dispatcher?.let { dispatcher = it }
                addHandler { request -> handle(request) }
            },
        )

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        requestCount++
        if (unreachable) throw IOException("simulated network failure")
        if (tlsFailure) throw SSLHandshakeException("simulated certificate failure")
        val path = request.url.encodedPath
        return when {
            path.endsWith("/jmap/session") -> sessionResponse()
            path.endsWith("/jmap/api/") -> {
                gate?.await()
                state.withLock { apiResponse(request) }
            }
            path.startsWith("/jmap/upload/") -> state.withLock { uploadResponse(request) }
            path.startsWith("/jmap/download/") -> downloadResponse(path)
            path.startsWith("/corvid/devices") -> devices.handle(this, request)
            path.startsWith("/i/") -> imageResponse(request, path)
            else -> webTier[path]?.handle(this, request) ?: respond("not found", HttpStatusCode.NotFound)
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

    private fun MockRequestHandleScope.imageResponse(
        request: HttpRequestData,
        path: String,
    ): HttpResponseData {
        imageAuthorizations += request.headers[HttpHeaders.Authorization]
        val bytes = proxiedImages[path.removePrefix("/i/")] ?: return respond("", HttpStatusCode.NotFound)
        return respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, listOf("image/png")))
    }

    private fun MockRequestHandleScope.downloadResponse(path: String): HttpResponseData {
        val (accountId, blobId) = path.removePrefix("/jmap/download/").split("/").let { it.getOrNull(0) to it.getOrNull(1) }
        if ((blobId in devices.keyBlobIds) != (accountId == devices.keysAccountId)) {
            return respond("blob not found", HttpStatusCode.NotFound)
        }
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

        val createdIds = mutableMapOf<String, String>()
        val results = mutableMapOf<String, JsonObject>()
        val failedCallIds = mutableSetOf<String>()
        val responses =
            calls
                .flatMap { invocation ->
                    // A real server never dispatches a call whose #-reference names a call
                    // that itself failed; it fails this one with invalidResultReference too.
                    val handled =
                        if (invocation.referencesAnyOf(failedCallIds)) {
                            listOf("error" to buildJsonObject { put("type", "invalidResultReference") })
                        } else {
                            dispatch(invocation.name, resolveReferences(invocation.arguments, results), createdIds)
                        }
                    val (name, body) = handled.first()
                    if (name == "error") failedCallIds += invocation.callId
                    results[invocation.callId] = body
                    handled.map { (responseName, responseBody) -> Invocation(responseName, responseBody, invocation.callId) }
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

    // RFC 8620 requires accountId on every /get, /query and /set; Core/echo is the one
    // method here that isn't account-scoped, so it's the one exempt from the check.
    // The first response is the call's own; any others are implicit calls it caused.
    private fun dispatch(
        name: String,
        arguments: JsonObject,
        createdIds: MutableMap<String, String>,
    ): List<Pair<String, JsonObject>> {
        if (name.substringAfterLast('/') in setOf("get", "query", "set") && "accountId" !in arguments) {
            return listOf("error" to problem("invalidArguments", "accountId is required"))
        }
        return when (name) {
            "Core/echo" -> listOf(name to arguments)
            "Mailbox/get" -> listOf(name to getResponse("s1", mailboxes, arguments, FakeMailbox::id) { it.toJson() })
            "Email/query" -> listOf(emailQuery(arguments))
            "Email/get" -> listOf(name to getResponse("s1", emails, arguments, FakeEmail::id) { it.toJson(arguments.requestedHeaders()) })
            "Thread/get" -> listOf(name to threadGet(arguments))
            "Identity/get" -> listOf(name to getResponse("i1", identities, arguments, FakeIdentity::id) { it.toJson() })
            "Email/set" -> listOf(name to emailSet(arguments, createdIds))
            "EmailSubmission/set" -> submissionSet(arguments, createdIds)
            "FileNode/query" -> listOf(fileNodeQuery(arguments))
            "FileNode/get" -> listOf(name to fileNodeGet(arguments))
            "FileNode/set" -> listOf(name to fileNodeSet(arguments))
            "SieveScript/get" -> listOf(name to getResponse("ss1", sieveScripts, arguments, FakeSieveScript::id) { it.toJson() })
            "SieveScript/set" -> listOf(name to sieveScriptSet(arguments, createdIds))
            else -> listOf("error" to buildJsonObject { put("type", "unknownMethod") })
        }
    }

    /** The items a /get asked for by `ids` (all of them when it named none), and the ids that matched nothing. */
    private fun <T> select(
        items: List<T>,
        arguments: JsonObject,
        idOf: (T) -> String,
    ): Pair<List<T>, List<String>> {
        val ids = arguments["ids"]?.jsonArray?.map { it.jsonPrimitive.content }
        val selected = if (ids == null) items else ids.mapNotNull { id -> items.find { idOf(it) == id } }
        return selected to ids.orEmpty().filter { id -> items.none { idOf(it) == id } }
    }

    private fun <T> getResponse(
        state: String,
        items: List<T>,
        arguments: JsonObject,
        idOf: (T) -> String,
        toJson: (T) -> JsonObject,
    ): JsonObject {
        val (selected, notFound) = select(items, arguments, idOf)
        return buildJsonObject {
            put("accountId", ACCOUNT_ID)
            put("state", state)
            put("list", JsonArray(selected.map(toJson)))
            put("notFound", JsonArray(notFound.map { JsonPrimitive(it) }))
        }
    }

    private fun setResponse(
        oldState: String,
        newState: String,
        created: Map<String, JsonObject> = emptyMap(),
        updated: Map<String, JsonObject> = emptyMap(),
        destroyed: List<String> = emptyList(),
        notCreated: Map<String, JsonObject> = emptyMap(),
        notUpdated: Map<String, JsonObject> = emptyMap(),
        notDestroyed: Map<String, JsonObject> = emptyMap(),
    ): JsonObject =
        buildJsonObject {
            put("accountId", ACCOUNT_ID)
            put("oldState", oldState)
            put("newState", newState)
            if (created.isNotEmpty()) putJsonObject("created") { created.forEach { (k, v) -> put(k, v) } }
            if (updated.isNotEmpty()) putJsonObject("updated") { updated.forEach { (k, v) -> put(k, v) } }
            if (destroyed.isNotEmpty()) put("destroyed", JsonArray(destroyed.map { JsonPrimitive(it) }))
            if (notCreated.isNotEmpty()) putJsonObject("notCreated") { notCreated.forEach { (k, v) -> put(k, v) } }
            if (notUpdated.isNotEmpty()) putJsonObject("notUpdated") { notUpdated.forEach { (k, v) -> put(k, v) } }
            if (notDestroyed.isNotEmpty()) putJsonObject("notDestroyed") { notDestroyed.forEach { (k, v) -> put(k, v) } }
        }

    private fun emailQuery(arguments: JsonObject): Pair<String, JsonObject> {
        beforeEmailQuery()
        val filter = arguments["filter"]?.jsonObject
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val anchor = arguments["anchor"]?.jsonPrimitive?.contentOrNull
        val anchorOffset = arguments["anchorOffset"]?.jsonPrimitive?.intOrNull ?: 0
        val position = arguments["position"]?.jsonPrimitive?.intOrNull ?: 0
        val ascending = arguments["sort"]?.jsonArray?.firstOrNull()?.jsonObject?.get("isAscending")?.jsonPrimitive?.booleanOrNull ?: false

        val filtered = emails.filter { it.matches(filter) }
        val sorted = if (ascending) filtered.sortedBy { it.receivedAt } else filtered.sortedByDescending { it.receivedAt }
        val collapse = arguments["collapseThreads"]?.jsonPrimitive?.booleanOrNull ?: false
        val matching = if (collapse) sorted.distinctBy { it.threadId } else sorted

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

    private fun emailSet(
        arguments: JsonObject,
        createdIds: MutableMap<String, String>,
    ): JsonObject {
        val creating = arguments["create"]?.jsonObject.orEmpty()
        val notCreated = if (refuseEmailCreate) creating.mapValues { problem("overQuota", "email refused") } else emptyMap()
        val created =
            creating.filterKeys { it !in notCreated }.mapValues { (creationId, spec) ->
                val id = "sent${nextEmailId++}"
                emails += spec.jsonObject.toFakeEmail(id)
                createdIds[creationId] = id
                buildJsonObject {
                    put("id", id)
                    put("threadId", id)
                }
            }
        val updated = mutableMapOf<String, JsonObject>()
        val notUpdated = mutableMapOf<String, JsonObject>()
        arguments["update"]?.jsonObject?.forEach { (id, patch) ->
            if (emails.none { it.id == id }) {
                notUpdated[id] = problem("notFound", "no such email")
            } else {
                emails.replaceAll { if (it.id == id) it.patched(patch.jsonObject) else it }
                emailPatches += id to patch.jsonObject
                updated[id] = JsonObject(emptyMap())
            }
        }
        val destroyed = arguments["destroy"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        emails.removeAll { it.id in destroyed }
        return setResponse(
            "s0",
            "s1",
            created = created,
            notCreated = notCreated,
            updated = updated,
            destroyed = destroyed,
            notUpdated = notUpdated,
        )
    }

    private fun submissionSet(
        arguments: JsonObject,
        createdIds: MutableMap<String, String>,
    ): List<Pair<String, JsonObject>> {
        val created = mutableMapOf<String, JsonObject>()
        val notCreated = mutableMapOf<String, JsonObject>()
        arguments["create"]?.jsonObject?.forEach { (creationId, spec) ->
            val identityId = spec.jsonObject.getValue("identityId").jsonPrimitive.content
            val email = emails.find { it.id == createdIds.resolve(spec.jsonObject.getValue("emailId").jsonPrimitive.content) }
            when {
                refuseSubmission -> notCreated[creationId] = problem("forbiddenToSend", "submission refused")
                identities.none { it.id == identityId } -> notCreated[creationId] = problem("invalidProperties", "no such identity")
                email == null -> notCreated[creationId] = problem("invalidProperties", "no such email")
                else -> {
                    val id = "sub${nextSubmissionId++}"
                    submissions += FakeSubmission(id, identityId, email.id)
                    createdIds[creationId] = id
                    created[creationId] = buildJsonObject { put("id", id) }
                }
            }
        }
        val updated = mutableMapOf<String, JsonObject>()
        for ((ref, patch) in arguments["onSuccessUpdateEmail"]?.jsonObject.orEmpty()) {
            val submission = submissions.find { it.id == createdIds.resolve(ref) } ?: continue
            emails.replaceAll { if (it.id == submission.emailId) it.patched(patch.jsonObject) else it }
            updated[submission.emailId] = JsonObject(emptyMap())
        }
        val own = "EmailSubmission/set" to setResponse("sub0", "sub1", created = created, notCreated = notCreated)
        return if (updated.isEmpty()) listOf(own) else listOf(own, "Email/set" to setResponse("s1", "s2", updated = updated))
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
        val properties = arguments["properties"]?.jsonArray?.map { it.jsonPrimitive.content }
        return getResponse("fs1", fileNodes, arguments, FakeFileNode::id) { it.toJson(properties) }
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
        return setResponse("fs0", "fs1", created = created, destroyed = destroyed, notCreated = notCreated, notDestroyed = notDestroyed)
    }

    private fun sieveScriptSet(
        arguments: JsonObject,
        createdIds: MutableMap<String, String>,
    ): JsonObject {
        val created = mutableMapOf<String, String>()
        val notCreated = mutableMapOf<String, JsonObject>()
        arguments["create"]?.jsonObject?.forEach { (creationId, spec) ->
            if (invalidSieve) {
                notCreated[creationId] = problem("invalidSieve", "script rejected")
                return@forEach
            }
            val id = "s${nextSieveScriptId++}"
            sieveScripts +=
                FakeSieveScript(
                    id = id,
                    name = spec.jsonObject.getValue("name").jsonPrimitive.content,
                    blobId = spec.jsonObject.getValue("blobId").jsonPrimitive.content,
                )
            created[creationId] = id
            createdIds[creationId] = id
        }
        val updated = mutableListOf<String>()
        val notUpdated = mutableMapOf<String, JsonObject>()
        arguments["update"]?.jsonObject?.forEach { (id, patch) ->
            val script = sieveScripts.find { it.id == id }
            when {
                script == null -> notUpdated[id] = problem("notFound", "no such script")
                invalidSieve -> notUpdated[id] = problem("invalidSieve", "script rejected")
                else -> {
                    patch.jsonObject["blobId"]?.let { script.blobId = it.jsonPrimitive.content }
                    updated += id
                }
            }
        }
        val activateTarget = arguments["onSuccessActivateScript"]?.jsonPrimitive?.content
        if (activateTarget != null && notCreated.isEmpty() && notUpdated.isEmpty()) {
            val id = createdIds.resolve(activateTarget)
            sieveScripts.forEach { it.isActive = it.id == id }
        }
        return setResponse(
            "ss0",
            "ss1",
            created = created.mapValues { (_, id) -> buildJsonObject { put("id", id) } },
            updated = updated.associateWith { JsonObject(emptyMap()) },
            notCreated = notCreated,
            notUpdated = notUpdated,
        )
    }

    private fun problem(
        type: String,
        description: String,
    ) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun Invocation.referencesAnyOf(callIds: Set<String>): Boolean =
        arguments.keys.filter { it.startsWith("#") }.any { key ->
            arguments.getValue(key).jsonObject.getValue("resultOf").jsonPrimitive.content in callIds
        }

    private fun threadGet(arguments: JsonObject): JsonObject {
        val threads = emails.groupBy { it.threadId }.mapValues { (_, members) -> members.sortedBy { it.receivedAt }.map { it.id } }
        val (selected, notFound) = select(threads.keys.toList(), arguments) { it }
        return buildJsonObject {
            put("accountId", ACCOUNT_ID)
            put("state", "t1")
            put(
                "list",
                JsonArray(
                    selected.map { id ->
                        buildJsonObject {
                            put("id", id)
                            put("emailIds", JsonArray(threads.getValue(id).map { JsonPrimitive(it) }))
                        }
                    },
                ),
            )
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
                for (capability in REQUIRED_CAPABILITIES) {
                    if (capability != missingCapability) putJsonObject(capability) {}
                }
            }
            if (unauthenticated) {
                putJsonObject("accounts") {}
                putJsonObject("primaryAccounts") {}
                put("username", "")
            } else {
                putJsonObject("accounts") {
                    putJsonObject(ACCOUNT_ID) { put("name", "verde") }
                    putJsonObject(devices.keysAccountId) {
                        put("name", "corvid-keys")
                        put("isPersonal", false)
                        put("isReadOnly", true)
                    }
                }
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

private fun FakeIdentity.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("email", email)
    }

/** A `#creationId` names an object created earlier in the same request. */
private fun Map<String, String>.resolve(reference: String): String? =
    if (reference.startsWith("#")) get(reference.removePrefix("#")) else reference

private fun FakeSieveScript.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("blobId", blobId)
        put("isActive", isActive)
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

private fun FakeEmail.toJson(headerProperties: List<String>): JsonObject =
    buildJsonObject {
        headerProperties.forEach { put(it, headerValue(it)) }
        put("id", id)
        put("threadId", threadId)
        put("subject", subject)
        // A real Email/get returns an EmailAddress[]|null, never a bare string.
        if (from == null) {
            put("from", null as String?)
        } else {
            putJsonArray("from") {
                addJsonObject {
                    put("name", fromName)
                    put("email", from)
                }
            }
        }
        putAddressList("to", to)
        putAddressList("cc", cc)
        putAddressList("bcc", bcc)
        putAddressList("replyTo", replyTo)
        putIdList("messageId", messageId)
        putIdList("inReplyTo", inReplyTo)
        putIdList("references", references)
        put("receivedAt", receivedAt)
        putJsonObject("keywords") { for (keyword in keywords) put(keyword, true) }
        preview?.let { put("preview", it) }
        if (body == null) {
            put("textBody", null as String?)
            put("htmlBody", null as String?)
            putJsonObject("bodyValues") {}
        } else {
            val (htmlPartId, htmlType, html) = if (htmlBody == null) Triple("1", bodyType, body) else Triple("2", "text/html", htmlBody)
            putJsonArray("textBody") { addJsonObject { putPart("1", bodyType, body) } }
            putJsonArray("htmlBody") { addJsonObject { putPart(htmlPartId, htmlType, html) } }
            putJsonObject("bodyValues") {
                putJsonObject("1") { putValue(body, bodyTruncated) }
                if (htmlBody != null) putJsonObject("2") { putValue(htmlBody, bodyTruncated) }
            }
        }
    }

private fun JsonObjectBuilder.putPart(
    partId: String,
    type: String,
    content: String,
) {
    put("partId", partId)
    put("type", type)
    put("size", content.length)
}

private fun JsonObjectBuilder.putValue(
    content: String,
    truncated: Boolean,
) {
    put("value", content)
    put("isTruncated", truncated)
    put("isEncodingProblem", false)
}

private fun JsonObjectBuilder.putAddressList(
    key: String,
    addresses: List<String>,
) {
    if (addresses.isEmpty()) {
        put(key, null as String?)
    } else {
        putJsonArray(key) {
            addresses.forEach { address ->
                addJsonObject {
                    put("name", null as String?)
                    put("email", address)
                }
            }
        }
    }
}

private fun JsonObject.toFakeEmail(id: String): FakeEmail {
    fun addresses(key: String) = (get(key) as? JsonArray)?.map { it.jsonObject.getValue("email").jsonPrimitive.content }.orEmpty()

    fun ids(key: String) = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    val partId = getValue("textBody").jsonArray.first().jsonObject.getValue("partId").jsonPrimitive.content
    return FakeEmail(
        id = id,
        threadId = id,
        mailboxIds = getValue("mailboxIds").jsonObject.keys,
        subject = get("subject")?.jsonPrimitive?.contentOrNull,
        from = addresses("from").firstOrNull(),
        receivedAt = "2024-01-01T00:00:00Z",
        keywords = get("keywords")?.jsonObject?.keys.orEmpty(),
        to = addresses("to"),
        cc = addresses("cc"),
        bcc = addresses("bcc"),
        inReplyTo = ids("inReplyTo"),
        references = ids("references"),
        body = getValue("bodyValues").jsonObject.getValue(partId).jsonObject.getValue("value").jsonPrimitive.content,
    )
}

private fun FakeEmail.patched(patch: JsonObject): FakeEmail =
    patch.entries.fold(this) { email, (path, value) ->
        val property = path.substringBefore("/")
        val key = path.substringAfter("/").replace("~1", "/").replace("~0", "~")
        val present = value !is JsonNull
        when (property) {
            "mailboxIds" -> email.copy(mailboxIds = if (present) email.mailboxIds + key else email.mailboxIds - key)
            "keywords" -> email.copy(keywords = if (present) email.keywords + key else email.keywords - key)
            else -> error("unsupported patch $path")
        }
    }

private fun JsonObjectBuilder.putIdList(
    key: String,
    ids: List<String>,
) {
    if (ids.isEmpty()) put(key, null as String?) else put(key, JsonArray(ids.map { JsonPrimitive(it) }))
}
