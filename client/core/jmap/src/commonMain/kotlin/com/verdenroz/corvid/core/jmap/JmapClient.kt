package com.verdenroz.corvid.core.jmap

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.net.ssl.SSLException

const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
const val SUBMISSION_CAPABILITY = "urn:ietf:params:jmap:submission"
const val FILENODE_CAPABILITY = "urn:ietf:params:jmap:filenode"
const val SIEVE_CAPABILITY = "urn:ietf:params:jmap:sieve"

class WebReply(val status: HttpStatusCode, val body: JsonObject?)

/**
 * A session against a rebased JMAP endpoint. [connect] discovers it; [call]
 * reuses the same [AuthLatch]-guarded request path and refetches the
 * session when the server reports a new `sessionState`.
 */
class JmapClient private constructor(
    private val httpClient: HttpClient,
    private val authLatch: AuthLatch,
    private val baseUrl: String,
    private val bearerToken: suspend () -> String?,
    session: JmapSession,
) {
    var session: JmapSession = session
        private set

    companion object {
        /**
         * [bearerToken] is read fresh on every request, not captured once: a
         * [JmapClient] outlives a single access token for as long as the
         * device stays unlocked, and [TokenSource] refreshes underneath it.
         */
        suspend fun connect(
            httpClient: HttpClient,
            authLatch: AuthLatch,
            baseUrl: String,
            bearerToken: suspend () -> String? = { null },
        ): JmapClient {
            val session = discoverSession(httpClient, authLatch, baseUrl, bearerToken)
            return JmapClient(httpClient, authLatch, baseUrl, bearerToken, session)
        }
    }

    /**
     * Runs one batch of calls and matches each response back to its callId.
     * A method error inside the batch is [MethodOutcome.Failure] for that
     * one callId, not a thrown exception: the rest of the batch still ran.
     */
    suspend fun call(invocations: List<Invocation>): Map<String, MethodOutcome> {
        val request = JmapRequest(using = REQUIRED_CAPABILITIES, methodCalls = invocations.map(::withAccountId))
        val response = guardedPost(httpClient, authLatch, session.apiUrl, bearerToken, request)
        val parsed =
            try {
                response.body<JmapResponse>()
            } catch (e: NoTransformationFoundException) {
                throw JmapException(JmapError.Request(type = "notJMAP", detail = e.message))
            } catch (e: SerializationException) {
                throw JmapException(JmapError.Request(type = "notJMAP", detail = e.message))
            }
        session =
            when {
                session.state == null -> session.copy(state = parsed.sessionState)
                parsed.sessionState != session.state -> discoverSession(httpClient, authLatch, baseUrl, bearerToken)
                else -> session
            }
        val responses = parsed.methodResponses.groupBy { it.callId }
        val missing = invocations.map { it.callId } - responses.keys
        if (missing.isNotEmpty()) {
            throw JmapException(JmapError.Request(type = "incompleteResponse", detail = "no response for $missing"))
        }
        return invocations.associate { it.callId to outcomeOf(it, responses.getValue(it.callId)) }
    }

    /** RFC 8620 requires accountId on every /get, /query and /set; callers never carry
     *  one themselves so it can never drift from the session that authenticated them. */
    private fun withAccountId(invocation: Invocation): Invocation {
        if ("accountId" in invocation.arguments) return invocation
        val accountId = session.accountId ?: throw JmapException(JmapError.Unauthorized)
        return invocation.copy(arguments = JsonObject(invocation.arguments + ("accountId" to JsonPrimitive(accountId))))
    }

    /** Not a method call: blobs move over plain HTTP through the templates the session advertises. */
    suspend fun uploadBlob(
        bytes: ByteArray,
        contentType: String,
    ): String {
        val url = session.uploadUrl.replace("{accountId}", session.accountId.orEmpty())
        val response =
            guarded(authLatch) {
                httpClient.post(url) {
                    bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    contentType(ContentType.parse(contentType))
                    setBody(bytes)
                }
            }
        return response.body<JsonObject>().getValue("blobId").jsonPrimitive.content
    }

    /**
     * A GET to the web tier outside JMAP, such as `/corvid/update`, with the same bearer token.
     * A 404 is null, not a failure: it means the server does not offer the path.
     */
    suspend fun webGet(path: String): JsonObject? {
        val reply = webSend(HttpMethod.Get, path)
        return if (reply.status == HttpStatusCode.OK) reply.body else null
    }

    suspend fun webPost(
        path: String,
        body: JsonObject,
    ) {
        webSend(HttpMethod.Post, path, body)
    }

    /**
     * Any web tier call, answered with whatever status came back. A status in [passThrough] is
     * returned instead of thrown and does not trip the auth latch, so a path's own 401 is not the
     * session's. A 200 or 201 body is read as JSON; any other status keeps its body only when it
     * is JSON, so a proxy's HTML error page reads as no body.
     */
    suspend fun webSend(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        passThrough: Set<HttpStatusCode> = setOf(HttpStatusCode.NotFound),
    ): WebReply {
        val response =
            guarded(authLatch, passThrough) {
                httpClient.request("$baseUrl$path") {
                    this.method = method
                    bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    headers.forEach { (name, value) -> header(name, value) }
                    body?.let {
                        contentType(ContentType.Application.Json)
                        setBody(it)
                    }
                }
            }
        // Only the forward-auth gate challenges; a path's own 401 carries no WWW-Authenticate.
        if (response.status == HttpStatusCode.Unauthorized && HttpHeaders.WWWAuthenticate in response.headers) {
            authLatch.trip()
            throw JmapException(JmapError.Unauthorized)
        }
        val hasBody = response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created
        return WebReply(response.status, if (hasBody) response.json() else response.jsonOrNull())
    }

    private suspend fun HttpResponse.jsonOrNull(): JsonObject? =
        try {
            body<JsonObject>()
        } catch (e: NoTransformationFoundException) {
            null
        } catch (e: ContentConvertException) {
            null
        } catch (e: SerializationException) {
            null
        }

    private suspend fun HttpResponse.json(): JsonObject =
        try {
            body<JsonObject>()
        } catch (e: NoTransformationFoundException) {
            throw JmapException(JmapError.Request(type = "notJSON", detail = e.message))
        } catch (e: SerializationException) {
            throw JmapException(JmapError.Request(type = "notJSON", detail = e.message))
        }

    /**
     * Pushes from the session's EventSource endpoint for as long as it is collected: one connection,
     * reopened on a drop. Ends with the [JmapException] of a terminal failure, which trips the latch on a 401.
     */
    fun stateChanges(): Flow<StateChange> = stateChangeFlow(httpClient, authLatch, { session.eventSourceUrl }, bearerToken)

    /** The address the client dialled, which the web tier's own paths hang off. */
    val webBase: String get() = baseUrl.trimEnd('/')

    /**
     * A GET carrying no bearer token, for a URL that is its own credential. Any failure is null,
     * and never trips the auth latch, since a missing image must not take the session down.
     */
    suspend fun fetchPublic(url: String): ByteArray? {
        val response =
            try {
                httpClient.get(url)
            } catch (e: IOException) {
                return null
            }
        return if (response.status.isSuccess()) response.body() else null
    }

    suspend fun downloadBlob(
        blobId: String,
        name: String,
        type: String,
        accountId: String? = session.accountId,
    ): ByteArray {
        val url =
            session.downloadUrl
                .replace("{accountId}", accountId.orEmpty())
                .replace("{blobId}", blobId)
                .replace("{name}", name)
                .replace("{type}", type)
        val response =
            guarded(authLatch) {
                httpClient.get(url) {
                    bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }
            }
        return response.body()
    }
}

/**
 * A call can be answered more than once under its callId: RFC 8621 has
 * EmailSubmission/set follow itself with an Email/set for onSuccessUpdateEmail.
 * The response named for the request's method (or an error) is the outcome;
 * the rest ride along as [MethodOutcome.Success.implicit].
 */
private fun outcomeOf(
    request: Invocation,
    responses: List<Invocation>,
): MethodOutcome {
    val own =
        responses.firstOrNull { it.name == request.name || it.name == "error" }
            ?: throw JmapException(JmapError.Request(type = "incompleteResponse", detail = "no response for ${request.callId}"))
    if (own.name == "error") {
        return MethodOutcome.Failure(
            JmapError.Method(
                type = own.arguments["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = own.arguments["description"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }
    return MethodOutcome.Success(own.arguments, responses - own)
}

private suspend fun discoverSession(
    httpClient: HttpClient,
    authLatch: AuthLatch,
    baseUrl: String,
    bearerToken: suspend () -> String?,
): JmapSession {
    val response = guardedGet(httpClient, authLatch, "$baseUrl/jmap/session", bearerToken)
    return parseSession(baseUrl, response.body())
}

internal suspend fun guardedGet(
    httpClient: HttpClient,
    authLatch: AuthLatch,
    url: String,
    bearerToken: suspend () -> String?,
): HttpResponse =
    guarded(authLatch) {
        httpClient.get(url) {
            bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

private suspend fun guardedPost(
    httpClient: HttpClient,
    authLatch: AuthLatch,
    url: String,
    bearerToken: suspend () -> String?,
    body: JmapRequest,
): HttpResponse =
    guarded(authLatch) {
        httpClient.post(url) {
            bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

/**
 * The one path every JMAP request takes: refuse outright while the latch is
 * tripped, classify a thrown failure before it reaches the caller as
 * [JmapError.Unreachable] or [JmapError.TlsFailure] (an [SSLException] is
 * also an [IOException], so it must be caught first), and trip the latch on
 * the first 401 rather than let a later call discover it too.
 */
private suspend fun guarded(
    authLatch: AuthLatch,
    passThrough: Set<HttpStatusCode> = emptySet(),
    request: suspend () -> HttpResponse,
): HttpResponse {
    val response = transport(authLatch, request)
    rejectFailure(authLatch, response, passThrough)
    return response
}

internal suspend fun <T> transport(
    authLatch: AuthLatch,
    block: suspend () -> T,
): T {
    if (authLatch.tripped.value) throw JmapException(JmapError.Unauthorized)
    return try {
        block()
    } catch (e: SSLException) {
        throw JmapException(JmapError.TlsFailure(e))
    } catch (e: IOException) {
        throw JmapException(JmapError.Unreachable(e))
    }
}

internal suspend fun rejectFailure(
    authLatch: AuthLatch,
    response: HttpResponse,
    passThrough: Set<HttpStatusCode> = emptySet(),
) {
    if (response.status in passThrough) return
    when (response.status) {
        HttpStatusCode.Unauthorized -> {
            authLatch.trip()
            throw JmapException(JmapError.Unauthorized)
        }
        HttpStatusCode.Forbidden -> throw JmapException(JmapError.Forbidden)
        HttpStatusCode.TooManyRequests -> {
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            throw JmapException(JmapError.RateLimited(retryAfter))
        }
    }
    // Every other 4xx is an RFC 7807 problem body (unknownCapability, notJSON, notRequest,
    // limit, ...): the request itself was rejected, so it must never be retried like a Server.
    if (response.status.value in 400..499) {
        val problem =
            try {
                response.body<JsonObject>()
            } catch (e: NoTransformationFoundException) {
                null
            } catch (e: SerializationException) {
                null
            }
        throw JmapException(
            JmapError.Request(
                type = problem?.get("type")?.jsonPrimitive?.contentOrNull ?: "unknown",
                detail = problem?.get("detail")?.jsonPrimitive?.contentOrNull,
            ),
        )
    }
    if (!response.status.isSuccess()) throw JmapException(JmapError.Server(response.status.value))
}

/**
 * A missing mail account means "not authenticated" (an anonymous session
 * carries capabilities and nothing else); a missing capability or URL is
 * refused outright, never rebased from an empty string.
 */
private fun parseSession(
    baseUrl: String,
    body: JsonObject,
): JmapSession {
    fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull

    val capabilities = body["capabilities"] as? JsonObject ?: JsonObject(emptyMap())
    for (required in REQUIRED_CAPABILITIES) {
        if (required !in capabilities) throw JmapSessionException("the session is missing capability $required")
    }

    val accountId =
        (body["primaryAccounts"] as? JsonObject)?.get(MAIL_CAPABILITY)?.jsonPrimitive?.contentOrNull
            ?: throw JmapException(JmapError.Unauthorized)

    fun requiredUrl(key: String): String {
        val advertised = text(key) ?: throw JmapSessionException("the session did not return $key")
        return rebase(baseUrl, advertised)
    }

    val core = capabilities[CORE_CAPABILITY] as? JsonObject ?: JsonObject(emptyMap())
    return JmapSession(
        apiUrl = requiredUrl("apiUrl"),
        downloadUrl = requiredUrl("downloadUrl"),
        uploadUrl = requiredUrl("uploadUrl"),
        eventSourceUrl = requiredUrl("eventSourceUrl"),
        accountId = accountId,
        state = text("state"),
        maxCallsInRequest = core["maxCallsInRequest"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        maxObjectsInGet = core["maxObjectsInGet"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
    )
}
