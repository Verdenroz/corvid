package com.verdenroz.corvid.core.testing

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException

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

    var requestCount: Int = 0
        private set

    val engine: MockEngine = MockEngine { request -> handle(request) }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        requestCount++
        if (unreachable) throw IOException("simulated network failure")
        return when {
            request.url.encodedPath.endsWith("/.well-known/jmap") -> sessionResponse()
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.sessionResponse(): HttpResponseData {
        if (unauthorized) return respond("", HttpStatusCode.Unauthorized)
        return respond(
            SESSION_BODY,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, listOf("application/json")),
        )
    }

    private companion object {
        val SESSION_BODY =
            """
            {
              "capabilities": {"urn:ietf:params:jmap:core": {}, "urn:ietf:params:jmap:mail": {}},
              "accounts": {"u1": {"name": "verde"}},
              "primaryAccounts": {"urn:ietf:params:jmap:mail": "u1"},
              "username": "verde",
              "apiUrl": "https://mail.corvid.test/jmap/api/",
              "downloadUrl": "https://mail.corvid.test/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
              "uploadUrl": "https://mail.corvid.test/jmap/upload/{accountId}/",
              "eventSourceUrl": "https://mail.corvid.test/jmap/eventsource/",
              "state": "s1"
            }
            """.trimIndent()
    }
}
