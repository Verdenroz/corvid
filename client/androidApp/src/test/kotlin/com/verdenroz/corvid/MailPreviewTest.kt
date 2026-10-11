package com.verdenroz.corvid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private fun push(json: String) = json.encodeToByteArray()

private fun mailPreview(
    content: ByteArray,
    decrypted: Boolean,
): MailPreview? = (pushNotice(content, decrypted) as? PushNotice.Mail)?.preview

class MailPreviewTest {
    @Test
    fun `a pushed message names its sender, subject and preview`() {
        val preview =
            mailPreview(
                push(
                    """{"v":1,"id":"e1","from":{"name":"Ada Lovelace","email":"ada@x.test"},"subject":"Engines","preview":"Notes on G"}""",
                ),
                decrypted = true,
            )

        assertEquals(MailPreview("e1", "Ada Lovelace", "Engines", "Notes on G"), preview)
    }

    @Test
    fun `a sender with no name is titled by address`() {
        val nameless =
            mailPreview(push("""{"v":1,"id":"e1","from":{"name":null,"email":"ada@x.test"},"subject":"s","preview":"p"}"""), true)
        val blank = mailPreview(push("""{"v":1,"id":"e1","from":{"name":" ","email":"ada@x.test"},"subject":"s","preview":"p"}"""), true)

        assertEquals("ada@x.test", nameless?.title)
        assertEquals("ada@x.test", blank?.title)
    }

    @Test
    fun `fields the app does not know are ignored`() {
        val preview =
            mailPreview(
                push("""{"v":1,"id":"e1","from":{"email":"ada@x.test","role":"x"},"subject":"s","preview":"p","receivedAt":"now"}"""),
                true,
            )

        assertEquals("e1", preview?.id)
    }

    @Test
    fun `a body the connector could not decrypt posts nothing, however well formed`() {
        assertNull(pushNotice(push("""{"v":1,"id":"e1","from":{"email":"ceo@bank.test"},"subject":"Wire now","preview":""}"""), false))
        assertNull(pushNotice(byteArrayOf(0x00, 0xff.toByte(), 0x13), false))
    }

    @Test
    fun `a decrypted body that is garbage, missing a field or another version posts a generic notice`() {
        val unreadable =
            listOf(
                byteArrayOf(0x00, 0xff.toByte(), 0x13),
                push("""{"v":1,"from":{"email":"ada@x.test"}}"""),
                push("""{"v":2,"id":"e1","from":{"email":"ada@x.test"},"subject":"s","preview":"p"}"""),
                push("""["v",1]"""),
            )
        for (content in unreadable) assertIs<PushNotice.Generic>(pushNotice(content, true))
    }

    @Test
    fun `an empty subject reads as no subject and the big text adds the preview below`() {
        val preview = MailPreview("e1", "Ada", "", "Notes on G")

        assertEquals("(no subject)", preview.text("(no subject)"))
        assertEquals("(no subject)\nNotes on G", preview.bigText("(no subject)"))
    }

    @Test
    fun `an empty preview leaves the big text as the subject alone`() {
        assertEquals("Engines", MailPreview("e1", "Ada", "Engines", "").bigText("(no subject)"))
    }
}
