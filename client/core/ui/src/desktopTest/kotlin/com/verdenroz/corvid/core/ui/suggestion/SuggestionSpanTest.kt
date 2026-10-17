package com.verdenroz.corvid.core.ui.suggestion

import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.resolve
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuggestionSpanTest {
    private val environment = composedResourceEnvironment()

    private suspend fun span(days: Int) = spanClause(days)?.resolve(environment)

    @Test
    fun `under two weeks the span is in days`() =
        runTest {
            assertEquals("over 1 day", span(1))
            assertEquals("over 13 days", span(13))
        }

    @Test
    fun `from two weeks the span is in the nearest whole weeks`() =
        runTest {
            assertEquals("over 2 weeks", span(14))
            assertEquals("over 3 weeks", span(20))
            assertEquals("over 5 weeks", span(35))
        }

    @Test
    fun `a server that did not say how long gives no span`() {
        assertNull(spanClause(0))
    }
}
