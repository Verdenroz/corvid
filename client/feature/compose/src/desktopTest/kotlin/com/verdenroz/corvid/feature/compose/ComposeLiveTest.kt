package com.verdenroz.corvid.feature.compose

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Sends one message through the compose view model, the outbox and the real client against a
 * running Stalwart, then counts what arrived. Runs only with CORVID_OUTBOX_LIVE=1, reading the
 * origin from CORVID_LIVE_URL, a bearer token from CORVID_LIVE_TOKEN and the mailbox address
 * from CORVID_LIVE_ADDRESS, which receives its own mail.
 */
class ComposeLiveTest {
    @Test
    fun `a message sent from compose through the outbox arrives once`() =
        runViewModelTest {
            if (System.getenv("CORVID_OUTBOX_LIVE") != "1") {
                println("skipped: set CORVID_OUTBOX_LIVE=1 against a running rig to run this")
                return@runViewModelTest
            }
            val address = checkNotNull(System.getenv("CORVID_LIVE_ADDRESS"))
            val token = checkNotNull(System.getenv("CORVID_LIVE_TOKEN"))
            val subject = "compose live ${System.currentTimeMillis()}"
            val client = JmapClient.connect(createJmapHttpClient(), AuthLatch(), checkNotNull(System.getenv("CORVID_LIVE_URL"))) { token }
            val compose = ComposeRepository(client, MailboxRepository(client), KeyedTextResolver)
            val outbox = TestOutbox(client, compose)
            val vm = track(ComposeViewModel(compose, outbox.outbox, ComposeIntent.New, sendWait = null))
            vm.state.first { it is ComposeUiState.Editing }
            vm.edit { copy(to = address, subject = subject, body = "through the outbox") }

            vm.send()

            assertEquals(ComposeUiState.Sent, vm.state.first { it !is ComposeUiState.Editing || !it.sending })
            val counts =
                withContext(Dispatchers.Default) {
                    delay(4000)
                    listOf("inbox", "sent", "drafts").associateWith { copies(client, subject, it) }
                }
            println("copies of '$subject' by mailbox: $counts")
            assertEquals(mapOf("inbox" to 1, "sent" to 1, "drafts" to 0), counts)
            outbox.close()
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
        assertIs<JsonArray>(found.getValue("ids"))
        return found.getValue("ids").jsonArray.size
    }
}
