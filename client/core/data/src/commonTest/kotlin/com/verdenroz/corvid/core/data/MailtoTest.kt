package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ComposeIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailtoTest {
    @Test
    fun `a mailto link becomes a message to its address with its subject`() {
        assertEquals(ComposeIntent.To("leave@list.test", "unsubscribe me"), mailtoIntent("mailto:leave@list.test?subject=unsubscribe%20me"))
    }

    @Test
    fun `a mailto link without a subject has none`() {
        assertEquals(ComposeIntent.To("leave@list.test", ""), mailtoIntent("MAILTO:leave@list.test"))
    }

    @Test
    fun `other parameters are ignored and non-ascii subjects decode`() {
        assertEquals(ComposeIntent.To("a@b.test", "café"), mailtoIntent("mailto:a@b.test?body=x&Subject=caf%C3%A9"))
    }

    @Test
    fun `a web link or a mailto with no address is not a message`() {
        assertNull(mailtoIntent("https://list.test/unsubscribe"))
        assertNull(mailtoIntent("mailto:?subject=x"))
    }
}
