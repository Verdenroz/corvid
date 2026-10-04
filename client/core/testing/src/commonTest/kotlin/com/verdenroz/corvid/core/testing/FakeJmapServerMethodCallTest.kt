package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.jmap.resultReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BASE_URL = "http://127.0.0.1:9999"

class FakeJmapServerMethodCallTest {
    @Test
    fun `Mailbox get returns only the requested ids and names the rest not found`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m1", name = "Inbox", role = "inbox")
            val client = connect(fake)

            val outcome =
                client.call(
                    listOf(
                        Invocation(
                            "Mailbox/get",
                            buildJsonObject {
                                put("accountId", "u1")
                                put("ids", JsonArray(listOf(JsonPrimitive("m1"), JsonPrimitive("missing"))))
                            },
                            "c0",
                        ),
                    ),
                ).getValue("c0")

            val result = assertIs<MethodOutcome.Success>(outcome).result
            assertEquals(1, result["list"]?.jsonArray?.size)
            assertEquals("Inbox", result["list"]?.jsonArray?.get(0)?.jsonObject?.get("name")?.jsonPrimitive?.content)
            assertEquals(listOf("missing"), result["notFound"]?.jsonArray?.map { it.jsonPrimitive.content })
        }

    @Test
    fun `a mailbox query followed by a back-referenced get resolves the ids server side`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m1", name = "Inbox", role = "inbox")
            fake.emails +=
                listOf(
                    FakeEmail(
                        id = "e1",
                        threadId = "t1",
                        mailboxIds = setOf("m1"),
                        subject = "older",
                        from = "a@example.test",
                        receivedAt = "2024-01-01T00:00:00Z",
                    ),
                    FakeEmail(
                        id = "e2",
                        threadId = "t2",
                        mailboxIds = setOf("m1"),
                        subject = "newer",
                        from = "b@example.test",
                        receivedAt = "2024-01-02T00:00:00Z",
                    ),
                )
            val client = connect(fake)

            val outcomes =
                client.call(
                    listOf(
                        Invocation("Email/query", buildJsonObject { putFilter("m1") }, "c0"),
                        Invocation(
                            "Email/get",
                            buildJsonObject {
                                put("#ids", resultReference(resultOf = "c0", name = "Email/query", path = "/ids"))
                            },
                            "c1",
                        ),
                    ),
                )

            val queryResult = assertIs<MethodOutcome.Success>(outcomes.getValue("c0")).result
            assertEquals(listOf("e2", "e1"), queryResult["ids"]?.jsonArray?.map { it.jsonPrimitive.content })

            val getResult = assertIs<MethodOutcome.Success>(outcomes.getValue("c1")).result
            assertEquals(
                listOf("e2", "e1"),
                getResult["list"]?.jsonArray?.map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
        }

    @Test
    fun `an anchor the query no longer has is a method error, not an empty page`() =
        runTest {
            val fake = FakeJmapServer()
            val client = connect(fake)

            val outcome =
                client.call(
                    listOf(
                        Invocation(
                            "Email/query",
                            buildJsonObject {
                                putFilter("m1")
                                put("anchor", "does-not-exist")
                            },
                            "c0",
                        ),
                    ),
                ).getValue("c0")

            val failure = assertIs<MethodOutcome.Failure>(outcome)
            assertEquals("anchorNotFound", failure.error.type)
        }

    @Test
    fun `an unknown method is a method error naming it, not a thrown exception`() =
        runTest {
            val fake = FakeJmapServer()
            val client = connect(fake)

            val outcome = client.call(listOf(Invocation("Thread/get", buildJsonObject {}, "c0"))).getValue("c0")

            assertTrue(assertIs<MethodOutcome.Failure>(outcome).error.type.isNotEmpty())
        }

    @Test
    fun `a limit problem response is refused outright, never backed off and retried`() =
        runTest {
            val fake = FakeJmapServer()
            fake.apiProblem = "urn:ietf:params:jmap:error:limit"
            val client = connect(fake)

            val thrown =
                assertFailsWith<JmapException> {
                    client.call(listOf(Invocation("Mailbox/get", buildJsonObject {}, "c0")))
                }

            val error = assertIs<JmapError.Request>(thrown.error)
            assertEquals("urn:ietf:params:jmap:error:limit", error.type)
        }

    @Test
    fun `a non-JMAP response body is a typed error, not a raw serialization crash`() =
        runTest {
            val fake = FakeJmapServer()
            fake.malformedApiResponse = true
            val client = connect(fake)

            val thrown =
                assertFailsWith<JmapException> {
                    client.call(listOf(Invocation("Mailbox/get", buildJsonObject {}, "c0")))
                }

            assertIs<JmapError.Request>(thrown.error)
        }

    @Test
    fun `a session with no state adopts a call's sessionState instead of refetching`() =
        runTest {
            val fake = FakeJmapServer()
            fake.omitSessionState = true
            val client = connect(fake)
            val requestsAfterConnect = fake.requestCount

            client.call(listOf(Invocation("Mailbox/get", buildJsonObject {}, "c0")))

            assertEquals("s1", client.session.state)
            assertEquals(requestsAfterConnect + 1, fake.requestCount)
        }

    @Test
    fun `a response missing one call's result is refused rather than silently dropped`() =
        runTest {
            val fake = FakeJmapServer()
            fake.dropCallId = "c0"
            val client = connect(fake)

            assertFailsWith<JmapException> {
                client.call(listOf(Invocation("Mailbox/get", buildJsonObject {}, "c0")))
            }
        }

    @Test
    fun `Identity get lists the identities the account may send as`() =
        runTest {
            val fake = FakeJmapServer()
            fake.identities += FakeIdentity(id = "i1", email = "verde@example.test")
            val client = connect(fake)

            val outcome = client.call(listOf(Invocation("Identity/get", buildJsonObject {}, "c0"))).getValue("c0")

            val list = assertIs<MethodOutcome.Success>(outcome).result.getValue("list").jsonArray
            assertEquals(listOf("verde@example.test"), list.map { it.jsonObject.getValue("email").jsonPrimitive.content })
        }

    @Test
    fun `a submission with onSuccessUpdateEmail moves the email and answers twice under its callId`() =
        runTest {
            val fake = FakeJmapServer()
            fake.identities += FakeIdentity(id = "i1", email = "verde@example.test")
            val client = connect(fake)

            val outcomes = client.call(sendInvocations())

            assertIs<MethodOutcome.Success>(outcomes.getValue("c0"))
            val submission = assertIs<MethodOutcome.Success>(outcomes.getValue("c1")).result
            assertEquals("sub1", submission.getValue("created").jsonObject.getValue("s").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(setOf("sent"), fake.emails.single().mailboxIds)
            assertEquals(emptySet(), fake.emails.single().keywords)
        }

    @Test
    fun `a refused submission leaves the email in drafts and reports notCreated`() =
        runTest {
            val fake = FakeJmapServer()
            fake.identities += FakeIdentity(id = "i1", email = "verde@example.test")
            fake.refuseSubmission = true
            val client = connect(fake)

            val outcomes = client.call(sendInvocations())

            val submission = assertIs<MethodOutcome.Success>(outcomes.getValue("c1")).result
            assertEquals(
                "forbiddenToSend",
                submission.getValue("notCreated").jsonObject.getValue("s").jsonObject.getValue("type").jsonPrimitive.content,
            )
            assertEquals(setOf("drafts"), fake.emails.single().mailboxIds)
            assertTrue(fake.submissions.isEmpty())
        }
}

private fun JsonObjectBuilder.putFilter(mailboxId: String) {
    put("filter", buildJsonObject { put("inMailbox", mailboxId) })
}

private suspend fun connect(fake: FakeJmapServer): JmapClient = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)

private fun sendInvocations() =
    listOf(
        Invocation(
            "Email/set",
            buildJsonObject {
                putJsonObject("create") {
                    putJsonObject("e") {
                        putJsonObject("mailboxIds") { put("drafts", true) }
                        putJsonObject("keywords") { put("\$draft", true) }
                        putJsonArray("from") { addJsonObject { put("email", "verde@example.test") } }
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
                    putJsonObject("s") {
                        put("identityId", "i1")
                        put("emailId", "#e")
                    }
                }
                putJsonObject("onSuccessUpdateEmail") {
                    putJsonObject("#s") {
                        put("mailboxIds/drafts", JsonNull)
                        put("mailboxIds/sent", true)
                        put("keywords/\$draft", JsonNull)
                    }
                }
            },
            "c1",
        ),
    )
