package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals

private fun text(
    body: String,
    disabled: Boolean,
) = messageText(body, disabled, Color.Blue, Color.Gray, "(disabled)") {}

class MessageTextTest {
    @Test
    fun `a link in ordinary mail is live and stops before trailing punctuation`() {
        val text = text("See https://example.test/a?b=1. Thanks", disabled = false)

        val links = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }

        assertEquals(listOf("https://example.test/a?b=1"), links)
        assertEquals("See https://example.test/a?b=1. Thanks", text.text)
    }

    @Test
    fun `a link in suspicious mail is not clickable and says why`() {
        val text = text("Confirm: https://paypa1-secure.test/confirm", disabled = true)

        assertEquals(emptyList(), text.getLinkAnnotations(0, text.length))
        assertEquals("Confirm: https://paypa1-secure.test/confirm (disabled)", text.text)
    }

    @Test
    fun `a body with no links passes through unchanged`() {
        assertEquals("Sunday?", text("Sunday?", disabled = true).text)
    }
}
