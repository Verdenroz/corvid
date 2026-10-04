package com.verdenroz.corvid.core.text

import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.reply_attribution
import com.verdenroz.corvid.core.text.resources.server_unreachable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UiTextTest {
    @Test
    fun `two references to the same resource and arguments are equal`() {
        assertEquals(uiText(Res.string.reply_attribution, "a", "b"), uiText(Res.string.reply_attribution, "a", "b"))
    }

    @Test
    fun `different arguments make different text`() {
        assertNotEquals(uiText(Res.string.reply_attribution, "a", "b"), uiText(Res.string.reply_attribution, "a", "c"))
    }

    @Test
    fun `different resources make different text`() {
        assertNotEquals(uiText(Res.string.server_unreachable), uiText(Res.string.reply_attribution))
    }
}
