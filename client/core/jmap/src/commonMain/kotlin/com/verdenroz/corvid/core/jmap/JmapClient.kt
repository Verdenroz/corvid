package com.verdenroz.corvid.core.jmap

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.net.ssl.SSLException

internal const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
internal const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
internal const val FILENODE_CAPABILITY = "urn:ietf:params:jmap:filenode"

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
        val outcomes = parsed.methodResponses.associate { it.callId to it.toOutcome() }
        val missing = invocations.map { it.callId } - outcomes.keys
        if (missing.isNotEmpty()) {
            throw JmapException(JmapError.Request(type = "incompleteResponse", detail = "no response for $missing"))
        }
        return outcomes
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

    suspend fun downloadBlob(
        blobId: String,
        name: String,
        type: String,
    ): ByteArray {
        val url =
            session.downloadUrl
                .replace("{accountId}", session.accountId.orEmpty())
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

private fun Invocation.toOutcome(): MethodOutcome =
    if (name == "error") {
        MethodOutcome.Failure(
            JmapError.Method(
                type = arguments["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = arguments["description"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    } else {
        MethodOutcome.Success(arguments)
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
    request: suspend () -> HttpResponse,
): HttpResponse {
    if (authLatch.tripped.value) throw JmapException(JmapError.Unauthorized)
    val response =
        try {
            request()
        } catch (e: SSLException) {
            throw JmapException(JmapError.TlsFailure(e))
        } catch (e: IOException) {
            throw JmapException(JmapError.Unreachable(e))
        }
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
    return response
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
