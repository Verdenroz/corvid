package com.verdenroz.corvid.feature.digest

import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.digest.resources.Res
import com.verdenroz.corvid.feature.digest.resources.digest_caveat
import com.verdenroz.corvid.feature.digest.resources.digest_more
import com.verdenroz.corvid.feature.digest.resources.digest_more_count
import com.verdenroz.corvid.feature.digest.resources.digest_span
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DigestTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `the span names both ends`() =
        runTest {
            fun span(count: Int) =
                UiText.Plural(
                    Res.plurals.digest_span,
                    count,
                    listOf(UiText.FromServer("Sun 5 May, 07:00"), UiText.FromServer("Mon 6 May, 07:00"), count),
                )

            assertEquals("Sun 5 May, 07:00 to Mon 6 May, 07:00 · 1 message", span(1).resolve(environment))
            assertEquals("Sun 5 May, 07:00 to Mon 6 May, 07:00 · 12 messages", span(12).resolve(environment))
        }

    @Test
    fun `the lists row counts what is hidden`() =
        runTest {
            fun more(count: Int) = UiText.Plural(Res.plurals.digest_more, count)

            assertEquals("1 more from a list", more(1).resolve(environment))
            assertEquals("4 more from lists", more(4).resolve(environment))
            assertEquals("+4", uiText(Res.string.digest_more_count, 4).resolve(environment))
        }

    @Test
    fun `the caveat says a model wrote the summary`() =
        runTest {
            assertEquals("model summary, can be wrong", uiText(Res.string.digest_caveat).resolve(environment))
        }
}
