package com.verdenroz.corvid.core.jmap

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one test here that hits a real socket: a Ktor server on a random
 * loopback port, through the real OkHttp engine, proving the rebase works
 * against an actual non-443 port rather than MockEngine's in-process path.
 */
class EmbeddedServerConnectTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var baseUrl: String

    @BeforeTest
    fun start() {
        server =
            embeddedServer(Netty, port = 0) {
                routing {
                    get("/.well-known/jmap") {
                        call.respondText(SESSION_BODY, ContentType.Application.Json)
                    }
                }
            }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    @AfterTest
    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun `a session discovered over a real socket rebases onto its loopback port`() =
        runTest {
            val client = createJmapHttpClient()

            val session = JmapClient.connect(client, AuthLatch(), baseUrl).session

            assertEquals("$baseUrl/jmap/api/", session.apiUrl)
            assertEquals("u1", session.accountId)
        }

    private companion object {
        val SESSION_BODY =
            """
            {
              "primaryAccounts": {"urn:ietf:params:jmap:mail": "u1"},
              "apiUrl": "https://mail.corvid.test/jmap/api/",
              "downloadUrl": "https://mail.corvid.test/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
              "uploadUrl": "https://mail.corvid.test/jmap/upload/{accountId}/",
              "eventSourceUrl": "https://mail.corvid.test/jmap/eventsource/",
              "state": "s1"
            }
            """.trimIndent()
    }
}
