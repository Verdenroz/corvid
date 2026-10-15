package com.verdenroz.corvid

import kotlin.test.Test
import kotlin.test.assertEquals

private fun notice(json: String) = readPushNotice(json.encodeToByteArray())

class PushNoticeTest {
    @Test
    fun `a v2 digest push names the digest`() {
        assertEquals(PushNotice.Digest("d7"), notice("""{"v":2,"kind":"digest","id":"d7"}"""))
    }

    @Test
    fun `a v2 push of a kind the app does not know shows nothing`() {
        assertEquals(PushNotice.Ignored, notice("""{"v":2,"kind":"reminder","id":"r1"}"""))
        assertEquals(PushNotice.Ignored, notice("""{"v":2,"id":"r1"}"""))
    }

    @Test
    fun `a v2 digest push with no id shows nothing`() {
        assertEquals(PushNotice.Ignored, notice("""{"v":2,"kind":"digest"}"""))
    }

    @Test
    fun `a v1 push is a mail preview as before`() {
        val expected = MailPreview("e1", "ada@x.test", "Engines", "Notes")

        assertEquals(
            PushNotice.Mail(expected),
            notice("""{"v":1,"id":"e1","from":{"email":"ada@x.test"},"subject":"Engines","preview":"Notes"}"""),
        )
    }

    @Test
    fun `a body that is garbage, a v1 missing its id or a version not known reads as unreadable`() {
        assertEquals(PushNotice.Unreadable, readPushNotice(byteArrayOf(0x00, 0xff.toByte(), 0x13)))
        assertEquals(PushNotice.Unreadable, notice("""{"v":1,"from":{"email":"ada@x.test"}}"""))
        assertEquals(PushNotice.Unreadable, notice("""{"v":3,"kind":"digest","id":"d7"}"""))
    }
}
