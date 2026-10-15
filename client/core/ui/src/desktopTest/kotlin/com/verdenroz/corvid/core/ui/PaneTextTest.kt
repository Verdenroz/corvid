package com.verdenroz.corvid.core.ui

import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.replace_body
import com.verdenroz.corvid.core.ui.resources.revoked_detail
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PaneTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `the replace warning keeps its quotes and apostrophe`() =
        runTest {
            assertEquals(
                "\"vacation\" is the active Sieve script. Activating Corvid's filters turns it off. It is not deleted.",
                uiText(Res.string.replace_body, "vacation").resolve(environment),
            )
        }

    @Test
    fun `the revoked pane resolves with its apostrophe intact`() =
        runTest {
            assertEquals(
                "This device's key is gone from the server. Enroll it again from a device that still has access.",
                uiText(Res.string.revoked_detail).resolve(environment),
            )
        }
}
