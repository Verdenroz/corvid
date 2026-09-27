package com.verdenroz.corvid.core.jmap

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.net.ssl.SSLException

private const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
private const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
private const val FILENODE_CAPABILITY = "urn:ietf:params:jmap:filenode"

/**
 * A session against a rebased JMAP endpoint. [connect] discovers it; method
 * calls (slice 3) reuse the same [AuthLatch]-guarded request path.
 */
class JmapClient private constructor(
    val session: JmapSession,
) {
    companion object {
        suspend fun connect(
            httpClient: HttpClient,
            authLatch: AuthLatch,
            baseUrl: String,
            bearerToken: String? = null,
        ): JmapClient {
            val response = guardedGet(httpClient, authLatch, "$baseUrl/jmap/session", bearerToken)
            return JmapClient(parseSession(baseUrl, response.body()))
        }
    }
}

/**
 * The one path every JMAP request takes: refuse outright while the latch is
 * tripped, classify a thrown failure before it reaches the caller as
 * [JmapError.Unreachable] or [JmapError.TlsFailure] (an [SSLException] is
 * also an [IOException], so it must be caught first), and trip the latch on
 * the first 401 rather than let a later call discover it too.
 */
internal suspend fun guardedGet(
    httpClient: HttpClient,
    authLatch: AuthLatch,
    url: String,
    bearerToken: String?,
): HttpResponse {
    if (authLatch.tripped.value) throw JmapException(JmapError.Unauthorized)
    val response =
        try {
            httpClient.get(url) {
                bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
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
    if (!response.status.isSuccess()) throw JmapException(JmapError.Server(response.status.value))
    return response
}

/**
 * A session is only usable once it actually grants what a signed-in client
 * needs: an account is a stand-in for "not authenticated" (decision 5's
 * anonymous session carries capabilities and nothing else), and a missing
 * capability or endpoint is a server that can't do what this client needs,
 * never a URL silently rebased from an empty string.
 */
private fun parseSession(
    baseUrl: String,
    body: JsonObject,
): JmapSession {
    fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull

    val capabilities = body["capabilities"] as? JsonObject ?: JsonObject(emptyMap())
    for (required in listOf(CORE_CAPABILITY, MAIL_CAPABILITY, FILENODE_CAPABILITY)) {
        if (required !in capabilities) throw JmapSessionException("the session is missing capability $required")
    }

    val accountId =
        (body["primaryAccounts"] as? JsonObject)?.get(MAIL_CAPABILITY)?.jsonPrimitive?.contentOrNull
            ?: throw JmapException(JmapError.Unauthorized)

    fun requiredUrl(key: String): String {
        val advertised = text(key) ?: throw JmapSessionException("the session did not return $key")
        return rebase(baseUrl, advertised)
    }

    return JmapSession(
        apiUrl = requiredUrl("apiUrl"),
        downloadUrl = requiredUrl("downloadUrl"),
        uploadUrl = requiredUrl("uploadUrl"),
        eventSourceUrl = requiredUrl("eventSourceUrl"),
        accountId = accountId,
        state = text("state"),
    )
}
