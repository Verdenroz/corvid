package com.verdenroz.corvid.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorvidKeywordsTest {
    @Test
    fun `a keyword the user typed is a user keyword`() {
        assertTrue(CorvidKeywords.isUserKeyword("receipts"))
    }

    @Test
    fun `corvid namespaced and dollar keywords are not user keywords`() {
        listOf("corvid-rule-x", "corvid-llm-label-x", "corvid-anything", "\$seen", "\$junk").forEach {
            assertFalse(CorvidKeywords.isUserKeyword(it), it)
        }
    }

    @Test
    fun `a rule keyword is never a user keyword`() {
        assertFalse(CorvidKeywords.isUserKeyword(CorvidKeywords.ruleKeyword("Receipts")))
    }

    @Test
    fun `a label becomes a lowercase ascii keyword under the rule prefix`() {
        assertEquals("corvid-rule-receipts-2024", CorvidKeywords.ruleKeyword("Receipts 2024"))
        assertEquals("corvid-rule--t-", CorvidKeywords.ruleKeyword("Été"))
    }

    @Test
    fun `each namespace reads as the label kind that wrote it`() {
        assertEquals(Label.Yours("receipts"), CorvidKeywords.labelOf("receipts"))
        assertEquals(Label.Labeler("receipts"), CorvidKeywords.labelOf("corvid-receipts"))
        assertEquals(Label.Guess("newsletter"), CorvidKeywords.labelOf("corvid-llm-label-newsletter"))
        assertEquals(Label.Rule("ops"), CorvidKeywords.labelOf("corvid-rule-ops"))
    }

    @Test
    fun `flags and the model's markers are not labels`() {
        listOf("\$seen", "\$flagged", "corvid-llm-done", "corvid-llm-oversized").forEach {
            assertEquals(null, CorvidKeywords.labelOf(it), it)
        }
    }
}
