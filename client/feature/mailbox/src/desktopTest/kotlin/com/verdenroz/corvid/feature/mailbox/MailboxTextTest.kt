package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_from
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MailboxTextTest {
    @Test
    fun `the sender line resolves with the address filled in`() =
        runTest {
            assertEquals("From: ada@example.test", uiText(Res.string.mailbox_from, "ada@example.test").resolve())
        }
}
