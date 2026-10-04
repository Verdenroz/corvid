package com.verdenroz.corvid.feature.compose

import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_not_sent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `a refused send names the server's reason`() =
        runTest {
            assertEquals(
                "Not sent: forbiddenToSend",
                uiText(Res.string.compose_not_sent, UiText.FromServer("forbiddenToSend")).resolve(environment),
            )
        }
}
