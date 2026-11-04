package com.verdenroz.corvid.core.jmap

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val ECHO = listOf(Invocation("Core/echo", JsonObject(emptyMap()), "c0"))

/** A raw socket server, since the behaviour under test is what the transport does when a connection is cut. */
private class CuttingServer : AutoCloseable {
    private val socket = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val posts = AtomicInteger()
    val cutNext = AtomicInteger()
    val heads = CopyOnWriteArrayList<String>()
    val closeAfterAnswer = AtomicBoolean()
    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    private val open = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                open += client
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        while (true) {
            val head = readHead(input) ?: return
            heads += head
            input.readNBytes(contentLength(head))
            if (head.startsWith("GET")) return respond(client, SESSION, "close")
            posts.incrementAndGet()
            if (cutNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return client.close()
            respond(client, """{"methodResponses":[["Core/echo",{},"c0"]],"sessionState":"s1"}""", "keep-alive")
            if (closeAfterAnswer.get()) return client.close()
        }
    }

    private fun contentLength(head: String): Int =
        head.lines().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: 0

    private fun readHead(input: BufferedInputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) return null
            head.append(next.toChar())
        }
        return head.toString()
    }

    private fun respond(
        client: Socket,
        json: String,
        connection: String,
    ) {
        val bytes = json.toByteArray()
        client.getOutputStream().apply {
            write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: $connection\r\n\r\n"
                    .toByteArray(),
            )
            write(bytes)
            flush()
        }
        if (connection == "close") client.close()
    }

    override fun close() {
        socket.close()
        open.forEach { runCatching { it.close() } }
    }

    private companion object {
        val SESSION =
            """
            {"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{},"urn:ietf:params:jmap:submission":{},
            "urn:ietf:params:jmap:filenode":{},"urn:ietf:params:jmap:sieve":{}},
            "primaryAccounts":{"urn:ietf:params:jmap:mail":"u1"},"apiUrl":"https://mail.corvid.test/jmap/api/",
            "downloadUrl":"https://mail.corvid.test/d/{accountId}/{blobId}/{name}?accept={type}",
            "uploadUrl":"https://mail.corvid.test/u/{accountId}/","eventSourceUrl":"https://mail.corvid.test/e/","state":"s1"}
            """.trimIndent().replace("\n", "")
    }
}

class SingleAttemptTest {
    private val server = CuttingServer()

    @AfterTest
    fun stop() = server.close()

    private suspend fun connect() = JmapClient.connect(createJmapHttpClient(), AuthLatch(), server.baseUrl)

    @Test
    fun `a single-attempt post whose connection is cut after it was sent is not sent again and reads as unreachable`() =
        runTest {
            val client = connect()
            client.call(ECHO)
            server.cutNext.set(1)

            val failure = assertFailsWith<JmapException> { client.call(ECHO, singleAttempt = true) }

            assertIs<JmapError.Unreachable>(failure.error)
            assertEquals(2, server.posts.get())
        }

    @Test
    fun `the same cut on an unmarked post is what makes the transport send it again`() =
        runTest {
            val client = connect()
            client.call(ECHO)
            server.cutNext.set(1)

            client.call(ECHO)

            assertEquals(3, server.posts.get())
        }

    @Test
    fun `the single-attempt header never reaches the wire on the default engine`() =
        runTest {
            connect().call(ECHO, singleAttempt = true)

            assertTrue(server.heads.none { it.contains("Single-Attempt", ignoreCase = true) })
        }

    @Test
    fun `the single-attempt header never reaches the wire on an engine without the interceptor`() =
        runTest {
            val client = JmapClient.connect(createJmapHttpClient(OkHttp.create()), AuthLatch(), server.baseUrl)

            client.call(ECHO, singleAttempt = true)

            assertTrue(server.heads.none { it.contains("Single-Attempt", ignoreCase = true) })
        }

    @Test
    fun `a single-attempt post on a pooled connection the server has since closed is still sent`() =
        runTest {
            val client = connect()
            server.closeAfterAnswer.set(true)
            client.call(ECHO)
            Thread.sleep(200)

            client.call(ECHO, singleAttempt = true)

            assertEquals(2, server.posts.get())
        }
}
