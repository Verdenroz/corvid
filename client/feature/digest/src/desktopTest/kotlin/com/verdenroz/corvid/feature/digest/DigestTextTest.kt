package com.verdenroz.corvid.feature.digest

import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.digest.resources.Res
import com.verdenroz.corvid.feature.digest.resources.digest_caveat
import com.verdenroz.corvid.feature.digest.resources.digest_span
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DigestTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `the span names both ends`() =
        runTest {
            val span = uiText(Res.string.digest_span, UiText.FromServer("Sun 5 May, 07:00"), UiText.FromServer("Mon 6 May, 07:00"))

            assertEquals("Mail from Sun 5 May, 07:00 to Mon 6 May, 07:00", span.resolve(environment))
        }

    @Test
    fun `the caveat says a model wrote the summary`() =
        runTest {
            assertEquals("Summary by a model; it can be wrong.", uiText(Res.string.digest_caveat).resolve(environment))
        }
}
