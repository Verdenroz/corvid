package com.verdenroz.corvid.core.text

import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.reply_attribution
import com.verdenroz.corvid.core.text.resources.server_unreachable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UiTextResolveTest {
    @Test
    fun `a resource resolves to its bundled text`() =
        runTest {
            assertEquals("Could not reach the server.", uiText(Res.string.server_unreachable).resolve())
        }

    @Test
    fun `an argument that is itself text is resolved before formatting`() =
        runTest {
            val text = uiText(Res.string.reply_attribution, "Sun 4 Oct, 02:28", UiText.FromServer("Ada Lovelace"))

            assertEquals("On Sun 4 Oct, 02:28, Ada Lovelace wrote:", text.resolve())
        }

    @Test
    fun `text from the server is shown exactly as it arrived`() =
        runTest {
            assertEquals("forbiddenToSend: no", UiText.FromServer("forbiddenToSend: no").resolve())
        }
}
