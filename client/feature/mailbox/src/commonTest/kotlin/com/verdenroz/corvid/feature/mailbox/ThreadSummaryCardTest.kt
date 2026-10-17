package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.model.ThreadSummary
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.custody_summarised_by
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private val AT = Instant.parse("2024-05-06T07:08:09Z")

private fun value(model: String) = summarisedValue(ThreadSummary("t", AT, model), TimeZone.UTC, AT)

class ThreadSummaryCardTest {
    @Test
    fun `the summarised value names the model and the local time`() {
        assertEquals(uiText(Res.string.custody_summarised_by, "qwen3", stamp(AT, TimeZone.UTC, AT)), value("qwen3"))
    }

    @Test
    fun `an older server that named no model leaves only the time`() {
        assertEquals(stamp(AT, TimeZone.UTC, AT), value(""))
    }
}
