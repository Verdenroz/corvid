package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.jmap.resultReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

private fun JsonObjectBuilder.putFilter(mailboxId: String) {
    put("filter", buildJsonObject { put("inMailbox", mailboxId) })
}

private suspend fun connect(fake: FakeJmapServer): JmapClient = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
