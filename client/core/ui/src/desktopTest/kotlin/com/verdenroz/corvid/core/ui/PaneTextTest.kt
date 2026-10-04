package com.verdenroz.corvid.core.ui

import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.revoked_title
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PaneTextTest {
    @Test
    fun `the revoked pane resolves with its apostrophe intact`() =
        runTest {
            assertEquals("This device's access was revoked", uiText(Res.string.revoked_title).resolve())
        }
}
