package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.CorvidKeywords
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mints an alias, takes mail on it over SMTP and replies from it, through the real client classes
 * against a running Stalwart and sentinel behind one web origin. It runs only with
 * CORVID_ALIAS_LIVE=1, reading the origin from CORVID_LIVE_URL, a passkey session's token from
 * CORVID_LIVE_TOKEN, the SMTP port from CORVID_LIVE_SMTP_PORT, and two admin tokens, since each is
 * spent by one request: CORVID_LIVE_MASTER disables the alias and CORVID_LIVE_MASTER_ENABLE turns
 * it back on.
 *
 *   CORVID_ALIAS_LIVE=1 CORVID_LIVE_URL=... ./gradlew :core:data:desktopTest --rerun \
 *     --tests '*AliasReplyLiveTest*' -i
 */
class AliasReplyLiveTest {
    @Test
    fun `a reply to mail on a live alias is sent from it and refused once the alias is disabled`() {
        if (System.getenv("CORVID_ALIAS_LIVE") != "1") {
            println("skipped: set CORVID_ALIAS_LIVE=1 against a running rig to run this")
            return
        }
        val url = env("CORVID_LIVE_URL")
        val token = env("CORVID_LIVE_TOKEN")
        val master = env("CORVID_LIVE_MASTER")
        val masterAgain = env("CORVID_LIVE_MASTER_ENABLE")
        val smtpPort = env("CORVID_LIVE_SMTP_PORT").toInt()

        runBlocking {
            val client = JmapClient.connect(createJmapHttpClient(), AuthLatch(), url) { token }
            val aliases = AliasRepository(client)
            val alias = assertIs<MintOutcome.Minted>(aliases.mint("live")).alias
            var disabled = false
            try {
                val subject = "live reply ${alias.name}"
                deliver(smtpPort, alias.address, subject)
                val originalId = arrived(client, subject)
                val mailbox = MailboxRepository(client)
                val original = assertIs<EmailLookup.Found>(mailbox.getEmail(originalId)).detail
                assertEquals(alias.address, original.arrivedVia)

                val compose = ComposeRepository(client, mailbox)
                val reply = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Reply(originalId, all = false))).draft
                assertEquals(alias.address, reply.from)
                val sentId = assertIs<DraftSaveResult.Saved>(compose.save(reply, replacing = null)).emailId
                assertEquals(SendResult.Sent, compose.send(sentId, reply.from))
                assertEquals(listOf(alias.address), fromOf(client, sentId))

                val heldId = assertIs<DraftSaveResult.Saved>(compose.save(reply.copy(body = "held"), replacing = null)).emailId
                assertIs<SwitchOutcome.Switched>(aliases.disable(alias.name, master))
                disabled = true
                assertEquals(AliasGone(alias.address), compose.send(heldId, reply.from))
                assertTrue(isDraft(client, heldId), "the refused reply is no longer a draft")
                assertEquals(listOf(alias.address), fromOf(client, heldId))
                val reread = assertIs<EmailLookup.Found>(MailboxRepository(client).getEmail(originalId)).detail
                assertNull(reread.arrivedVia)
                compose.discard(heldId)
            } finally {
                if (disabled) assertIs<SwitchOutcome.Switched>(aliases.enable(alias.name, masterAgain))
            }
        }
    }

    private fun env(name: String): String = checkNotNull(System.getenv(name)) { "$name is not set" }

    private suspend fun arrived(
        client: JmapClient,
        subject: String,
    ): String {
        val query = buildJsonObject { putJsonObject("filter") { put("subject", subject) } }
        repeat(40) {
            client.callOne("Email/query", query).getValue("ids").jsonArray.firstOrNull()?.let { return it.jsonPrimitive.content }
            delay(250)
        }
        error("nothing arrived with subject '$subject'")
    }

    private suspend fun email(
        client: JmapClient,
        id: String,
        property: String,
    ): JsonObject {
        val arguments =
            buildJsonObject {
                putJsonArray("ids") { add(JsonPrimitive(id)) }
                putJsonArray("properties") { add(JsonPrimitive(property)) }
            }
        return client.callOne("Email/get", arguments).getValue("list").jsonArray.single().jsonObject
    }

    private suspend fun fromOf(
        client: JmapClient,
        id: String,
    ): List<String> = email(client, id, "from").getValue("from").jsonArray.map { it.jsonObject.getValue("email").jsonPrimitive.content }

    private suspend fun isDraft(
        client: JmapClient,
        id: String,
    ): Boolean = CorvidKeywords.DRAFT in email(client, id, "keywords").getValue("keywords").jsonObject

    /** One SMTP session that ends with QUIT, since Stalwart bans an address for one malformed command. */
    private fun deliver(
        port: Int,
        to: String,
        subject: String,
    ) {
        Socket("127.0.0.1", port).use { socket ->
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream().bufferedWriter()

            fun reply(expected: Char) {
                var line: String
                do {
                    line = checkNotNull(input.readLine()) { "the SMTP server closed the connection" }
                } while (line.length > 3 && line[3] == '-')
                check(line.firstOrNull() == expected) { "SMTP answered '$line'" }
            }

            fun send(
                command: String,
                expected: Char = '2',
            ) {
                output.write("$command\r\n")
                output.flush()
                reply(expected)
            }
            reply('2')
            send("EHLO outside.test")
            send("MAIL FROM:<ada@outside.test>")
            send("RCPT TO:<$to>")
            send("DATA", expected = '3')
            val stamp = System.currentTimeMillis()
            send(
                listOf(
                    "From: Ada Lovelace <ada@outside.test>",
                    "To: $to",
                    "Subject: $subject",
                    "Message-ID: <live-$stamp@outside.test>",
                    "Date: Sat, 10 Oct 2026 12:00:00 +0000",
                    "",
                    "hello",
                    ".",
                ).joinToString("\r\n"),
            )
            send("QUIT")
        }
    }
}
