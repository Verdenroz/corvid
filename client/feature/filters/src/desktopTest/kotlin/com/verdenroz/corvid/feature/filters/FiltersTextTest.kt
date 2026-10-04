package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_replace_body
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FiltersTextTest {
    @Test
    fun `the replace warning keeps its quotes and apostrophe`() =
        runTest {
            assertEquals(
                "\"vacation\" is the active Sieve script. Activating Corvid's filters turns it off. It is not deleted.",
                uiText(Res.string.filters_replace_body, UiText.FromServer("vacation")).resolve(),
            )
        }
}
