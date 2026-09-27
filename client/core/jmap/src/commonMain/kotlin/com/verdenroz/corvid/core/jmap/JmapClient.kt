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

private const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"

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
            val response = guardedGet(httpClient, authLatch, "$baseUrl/.well-known/jmap", bearerToken)
            return JmapClient(parseSession(baseUrl, response.body()))
        }
    }
}

/**
 * The one path every JMAP request takes: refuse outright while the latch is
 * tripped, map a network failure to [JmapError.Unreachable], and trip the
 * latch on the first 401 rather than let a later call discover it too.
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
        } catch (e: IOException) {
            throw JmapException(JmapError.Unreachable(e))
        }
    if (response.status == HttpStatusCode.Unauthorized) {
        authLatch.trip()
        throw JmapException(JmapError.Unauthorized)
    }
    if (response.status == HttpStatusCode.Forbidden) throw JmapException(JmapError.Forbidden)
    if (!response.status.isSuccess()) throw JmapException(JmapError.Server(response.status.value))
    return response
}

private fun parseSession(
    baseUrl: String,
    body: JsonObject,
): JmapSession {
    fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull
    val accountId = (body["primaryAccounts"] as? JsonObject)?.get(MAIL_CAPABILITY)?.jsonPrimitive?.contentOrNull
    return JmapSession(
        apiUrl = rebase(baseUrl, text("apiUrl").orEmpty()),
        downloadUrl = rebase(baseUrl, text("downloadUrl").orEmpty()),
        uploadUrl = rebase(baseUrl, text("uploadUrl").orEmpty()),
        eventSourceUrl = rebase(baseUrl, text("eventSourceUrl").orEmpty()),
        accountId = accountId,
        state = text("state"),
    )
}
