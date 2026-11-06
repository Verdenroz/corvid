package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.resultReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private fun createAndSubmit() =
    listOf(
        Invocation(
            "Email/set",
            buildJsonObject {
                putJsonObject("create") {
                    putJsonObject("draft") {
                        putJsonObject("mailboxIds") { put("drafts", true) }
                        putJsonObject("keywords") {
                            put("\$draft", true)
                            put("corvid-outbox-1", true)
                        }
                        putJsonArray("messageId") { add(JsonPrimitive("one@example.test")) }
                        putJsonArray("from") { addJsonObject { put("email", SENDABLE_ACCOUNT_EMAIL) } }
                        putJsonArray("to") { addJsonObject { put("email", "a@example.test") } }
                        put("subject", "hello")
                        putJsonArray("textBody") { addJsonObject { put("partId", "1") } }
                        putJsonObject("bodyValues") { putJsonObject("1") { put("value", "hi") } }
                    }
                }
            },
            "c0",
        ),
        Invocation(
            "EmailSubmission/set",
            buildJsonObject {
                putJsonObject("create") {
                    putJsonObject("submission") {
                        put("identityId", "i1")
                        put("emailId", "#draft")
                    }
                }
                putJsonObject("onSuccessUpdateEmail") {
                    putJsonObject("#submission") {
                        put("mailboxIds/drafts", JsonNull)
                        put("mailboxIds/sent", true)
                        put("keywords/\$draft", JsonNull)
                    }
                }
            },
            "c1",
        ),
    )

class FakeJmapServerOutboxTest {
    @Test
    fun `creating a draft and submitting it by creation reference in one request files it in sent with its marks`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()

            val outcomes = fake.client().call(createAndSubmit())

            assertIs<MethodOutcome.Success>(outcomes.getValue("c1"))
            val email = fake.emails.single()
            assertEquals(setOf("sent"), email.mailboxIds)
            assertEquals(listOf("one@example.test"), email.messageId)
            assertEquals(setOf("corvid-outbox-1"), email.keywords)
            assertEquals(1, fake.submissions.size)
        }

    @Test
    fun `a message carrying a keyword is found by it in whichever mailbox it is in`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            val client = fake.client()
            client.call(createAndSubmit())

            val found =
                client.call(
                    listOf(
                        Invocation(
                            "Email/query",
                            buildJsonObject { putJsonObject("filter") { put("hasKeyword", "corvid-outbox-1") } },
                            "q",
                        ),
                        Invocation(
                            "Email/get",
                            buildJsonObject {
                                put("#ids", resultReference("q", "Email/query", "/ids"))
                                putJsonArray("properties") { add(JsonPrimitive("mailboxIds")) }
                            },
                            "g",
                        ),
                    ),
                ).getValue("g")

            assertEquals(1, assertIs<MethodOutcome.Success>(found).result.getValue("list").jsonArray.size)
        }

    @Test
    fun `a lost response is applied by the server and reaches the client as unreachable`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            val client = fake.client()
            fake.loseResponses = 1

            val failure = assertFailsWith<JmapException> { client.call(createAndSubmit()) }

            assertIs<JmapError.Unreachable>(failure.error)
            assertEquals(1, fake.submissions.size)
            assertEquals(0, fake.loseResponses)
        }
}
