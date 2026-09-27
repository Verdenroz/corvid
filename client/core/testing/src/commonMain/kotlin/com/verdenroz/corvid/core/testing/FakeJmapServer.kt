package com.verdenroz.corvid.core.testing

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

private const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
private const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
private const val FILENODE_CAPABILITY = "urn:ietf:params:jmap:filenode"

/**
 * Interprets JMAP against an in-memory mailbox, advertising
 * `https://mail.corvid.test/jmap/...` with no port, the way a real Stalwart
 * deployment does before the caller rebases it. Slice 2 wires up session
 * discovery only; method calls and FileNode/blob endpoints join in slices
 * 3 and 5.
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

    var requestCount: Int = 0
        private set

    val engine: MockEngine = MockEngine { request -> handle(request) }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        requestCount++
        if (unreachable) throw IOException("simulated network failure")
        if (tlsFailure) throw SSLHandshakeException("simulated certificate failure")
        return when {
            request.url.encodedPath.endsWith("/jmap/session") -> sessionResponse()
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
                putJsonObject("accounts") { putJsonObject("u1") { put("name", "verde") } }
                putJsonObject("primaryAccounts") { put(MAIL_CAPABILITY, "u1") }
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
