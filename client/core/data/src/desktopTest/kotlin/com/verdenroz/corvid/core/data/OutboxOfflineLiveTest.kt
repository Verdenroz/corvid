package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.data.outbox.Drained
import com.verdenroz.corvid.core.data.outbox.ItemResult
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxSender
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.data.outbox.OutboxWriter
import com.verdenroz.corvid.core.data.outbox.WriteOutcome
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
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Queues one message through the writer with only the public key, then derives the key from the device
 * key as an unlock does, drains with the real sender and client against a running Stalwart, and counts
 * what arrived. It runs only with CORVID_OUTBOX_LIVE=1, reading the origin from CORVID_LIVE_URL, a
 * bearer token from CORVID_LIVE_TOKEN and the mailbox address from CORVID_LIVE_ADDRESS, which receives
 * its own mail.
 *
 *   CORVID_OUTBOX_LIVE=1 CORVID_LIVE_URL=... ./gradlew :core:data:desktopTest --rerun \
 *     --tests '*OutboxOfflineLiveTest*' -i
 */
class OutboxOfflineLiveTest {
    @Test
    fun `a message queued with only the public key is delivered once after the key opens it`() {
        if (System.getenv("CORVID_OUTBOX_LIVE") != "1") {
            println("skipped: set CORVID_OUTBOX_LIVE=1 against a running rig to run this")
            return
        }
        val token = env("CORVID_LIVE_TOKEN")
        val address = env("CORVID_LIVE_ADDRESS")
        val deviceKey = ByteArray(33) { (it * 3 + 1).toByte() }
        val subject = "outbox offline live ${System.currentTimeMillis()}"
        val directory = createTempDirectory().toFile()

        runBlocking {
            val sealKey = OutboxSealer.keyFor(deviceKey)
            val writer = OutboxWriter(directory, sealKey, address.substringAfter('@'))
            val queued = writer.write(Draft(to = listOf(EmailAddress(null, address)), subject = subject, body = "written while locked"))
            val id = assertIs<WriteOutcome.Queued>(queued).id
            val onDisk = directory.walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }
            check(subject !in onDisk && "written while locked" !in onDisk) { "plaintext reached the outbox directory" }

            val client = JmapClient.connect(createJmapHttpClient(), AuthLatch(), env("CORVID_LIVE_URL")) { token }
            val compose = ComposeRepository(client, MailboxRepository(client))
            val store = checkNotNull(OutboxStore.open(directory))
            val sender = OutboxSender(store, client, compose, deviceKey)

            val first = sender.drain()
            println("first drain: $first")
            assertEquals(listOf(Drained(id, ItemResult.Sent)), first)
            val second = sender.drain()
            println("second drain: $second")
            assertEquals(emptyList(), second)

            delay(4000)
            val counts = listOf("inbox", "sent", "drafts").associateWith { copies(client, subject, it) }
            println("copies of '$subject' by mailbox: $counts")
            assertEquals(mapOf("inbox" to 1, "sent" to 1, "drafts" to 0), counts)
            store.close()
        }
    }

    private fun env(name: String): String = checkNotNull(System.getenv(name)) { "$name is not set" }

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
