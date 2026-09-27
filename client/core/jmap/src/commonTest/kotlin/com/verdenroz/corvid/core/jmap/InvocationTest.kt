package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class InvocationTest {
    @Test
    fun `an invocation serializes as a three element array`() {
        val invocation =
            Invocation(
                name = "Mailbox/get",
                arguments = buildJsonObject { put("accountId", "u1") },
                callId = "c0",
            )

        val encoded = JmapJson.encodeToString(Invocation.serializer(), invocation)

        assertEquals("""["Mailbox/get",{"accountId":"u1"},"c0"]""", encoded)
    }

    @Test
    fun `a three element array round trips back to the same invocation`() {
        val json = """["Email/query",{"filter":{"inMailbox":"m1"}},"c1"]"""

        val decoded = JmapJson.decodeFromString(Invocation.serializer(), json)

        assertEquals("Email/query", decoded.name)
        assertEquals("c1", decoded.callId)
        assertEquals("m1", decoded.arguments["filter"]?.jsonObject?.get("inMailbox")?.jsonPrimitive?.content)
    }
}
