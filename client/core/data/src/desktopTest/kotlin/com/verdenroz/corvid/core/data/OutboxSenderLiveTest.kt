package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.data.outbox.Drained
import com.verdenroz.corvid.core.data.outbox.ItemResult
import com.verdenroz.corvid.core.data.outbox.OutboxQueue
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxSender
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sends one queued message through the real sender and client against a running Stalwart, with the
 * response to the send dropped after the server has answered it, then counts what arrived. It runs
 * only with CORVID_OUTBOX_LIVE=1, reading the origin from CORVID_LIVE_URL, a bearer token for the
 * mailbox from CORVID_LIVE_TOKEN and that mailbox's address from CORVID_LIVE_ADDRESS, which
 * receives its own mail.
 *
 *   CORVID_OUTBOX_LIVE=1 CORVID_LIVE_URL=... ./gradlew :core:data:desktopTest --rerun \
 *     --tests '*OutboxSenderLiveTest*' -i
 */
class OutboxSenderLiveTest {
    @Test
    fun `a send whose response is dropped is delivered once and the retry finds it by keyword`() {
        if (System.getenv("CORVID_OUTBOX_LIVE") != "1") {
            println("skipped: set CORVID_OUTBOX_LIVE=1 against a running rig to run this")
            return
        }
        val origin = URI(env("CORVID_LIVE_URL"))
        val token = env("CORVID_LIVE_TOKEN")
        val address = env("CORVID_LIVE_ADDRESS")
        val deviceKey = ByteArray(33) { (it * 3 + 1).toByte() }
        val subject = "outbox live ${System.currentTimeMillis()}"

        DroppingProxy(origin.host, origin.port).use { proxy ->
            runBlocking {
                val client = JmapClient.connect(createJmapHttpClient(), AuthLatch(), "http://127.0.0.1:${proxy.port}") { token }
                val compose = ComposeRepository(client, MailboxRepository(client))
                compose.routeFor(null)
                val store = checkNotNull(OutboxStore.open(createTempDirectory().toFile()))
                val queue = OutboxQueue(store, OutboxSealer.keyFor(deviceKey), address.substringAfter('@'))
                val id = queue.enqueue(Draft(to = listOf(EmailAddress(null, address)), subject = subject, body = "probe"), null)
                val sender = OutboxSender(store, client, compose, deviceKey)

                proxy.dropNextResponse.set(true)
                val first = sender.drain()
                println("first drain (response dropped): $first")
                assertEquals(listOf(Drained(id, ItemResult.Unconfirmed(transportFailed = true))), first)

                val second = sender.drain()
                println("second drain (probe): $second")
                assertEquals(listOf(Drained(id, ItemResult.Sent)), second)

                val inbox = awaitCopies(client, subject, "inbox")
                delay(4000)
                val counts = listOf("inbox", "sent", "drafts").associateWith { copies(client, subject, it) }
                println("copies of '$subject' by mailbox: $counts (first inbox poll saw $inbox)")
                assertEquals(mapOf("inbox" to 1, "sent" to 1, "drafts" to 0), counts)
            }
        }
    }

    private fun env(name: String): String = checkNotNull(System.getenv(name)) { "$name is not set" }

    private suspend fun awaitCopies(
        client: JmapClient,
        subject: String,
        role: String,
    ): Int {
        repeat(30) {
            copies(client, subject, role).takeIf { it > 0 }?.let { return it }
            delay(1000)
        }
        return 0
    }

    private suspend fun copies(
        client: JmapClient,
        subject: String,
        role: String,
    ): Int {
        val boxes =
            client.callOne("Mailbox/get", buildJsonObject { put("properties", JsonArray(listOf(JsonPrimitive("role")))) })
                .getValue("list").jsonArray.map { it.jsonObject }
        val box = boxes.single { it["role"]?.jsonPrimitive?.content == role }.getValue("id").jsonPrimitive.content
        val found =
            client.callOne(
                "Email/query",
                buildJsonObject {
                    putJsonObject("filter") {
                        put("inMailbox", box)
                        put("subject", subject)
                    }
                },
            )
        return found.getValue("ids").jsonArray.size
    }
}

/** Forwards bytes both ways and, once armed, closes the connection the instant the server starts to answer. */
private class DroppingProxy(host: String, port: Int) : AutoCloseable {
    val dropNextResponse = AtomicBoolean(false)
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val upstream = Socket(host, port)
                thread(isDaemon = true) { pump(client, upstream, drop = false) }
                thread(isDaemon = true) { pump(upstream, client, drop = true) }
            }
        }
    }

    private fun pump(
        from: Socket,
        to: Socket,
        drop: Boolean,
    ) {
        val buffer = ByteArray(65536)
        runCatching {
            while (true) {
                val read = from.getInputStream().read(buffer)
                if (read < 0) break
                if (drop && dropNextResponse.compareAndSet(true, false)) {
                    println("proxy: server answered ($read bytes), dropping the connection before the client reads it")
                    break
                }
                to.getOutputStream().write(buffer, 0, read)
            }
        }
        runCatching { from.close() }
        runCatching { to.close() }
    }

    override fun close() = server.close()
}
