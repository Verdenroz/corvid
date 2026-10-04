package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.text.resolve
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MailDatesResolveTest {
    @Test
    fun `a stamp resolves to the English day, month and time`() =
        runTest {
            val now = Instant.parse("2026-10-04T12:00:00Z")

            assertEquals("Sun 4 Oct, 02:28", stamp(Instant.parse("2026-10-04T02:28:00Z"), TimeZone.UTC, now).resolve())
            assertEquals("Sun 27 Sep 2015, 19:40", stamp(Instant.parse("2015-09-27T19:40:00Z"), TimeZone.UTC, now).resolve())
        }

    @Test
    fun `a day header resolves to English words`() =
        runTest {
            val heading = dayHeading(LocalDate(2026, 10, 4), LocalDate(2026, 10, 4))

            assertEquals("Today", heading.title.resolve())
            assertEquals("Sunday 4 October", heading.detail.resolve())
        }
}
