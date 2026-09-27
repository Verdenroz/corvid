package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultReferenceTest {
    @Test
    fun `a result reference names the call, the method and the path`() {
        val reference = resultReference(resultOf = "c0", name = "Email/query", path = "/ids")

        assertEquals("c0", reference.getValue("resultOf").jsonPrimitive.content)
        assertEquals("Email/query", reference.getValue("name").jsonPrimitive.content)
        assertEquals("/ids", reference.getValue("path").jsonPrimitive.content)
    }
}
