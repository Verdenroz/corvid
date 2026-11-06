package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Identity
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val IDENTITY = Identity("i1", "Verde", "verde@example.test")
private val DRAFT = Draft(to = listOf(EmailAddress(null, "a@example.test")), subject = "hi", body = "text")

class OutboxPayloadsTest {
    @Test
    fun `a draft made for the outbox names its message id and keyword`() {
        val payload = createEmail(IDENTITY, DRAFT, "drafts", "one@example.test", "corvid-outbox-1")
        val created = payload.getValue("create").jsonObject.getValue("draft").jsonObject

        assertEquals("one@example.test", created.getValue("messageId").jsonArray.single().jsonPrimitive.content)
        assertEquals(setOf("\$draft", "\$seen", "corvid-outbox-1"), created.getValue("keywords").jsonObject.keys)
    }

    @Test
    fun `a draft saved from the editor carries neither`() {
        val created = createEmail(IDENTITY, DRAFT, "drafts").getValue("create").jsonObject.getValue("draft").jsonObject

        assertNull(created["messageId"])
        assertEquals(setOf("\$draft", "\$seen"), created.getValue("keywords").jsonObject.keys)
    }

    @Test
    fun `finding by keyword queries all mailboxes and reads the hits through a reference`() {
        val (query, read) = findByKeyword("corvid-outbox-1")

        assertEquals("corvid-outbox-1", query.arguments.getValue("filter").jsonObject.getValue("hasKeyword").jsonPrimitive.content)
        assertEquals("find", read.arguments.getValue("#ids").jsonObject.getValue("resultOf").jsonPrimitive.content)
    }
}
