package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.resolve
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MailDatesResolveTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `a stamp resolves to the English day, month and time`() =
        runTest {
            val now = Instant.parse("2026-10-04T12:00:00Z")

            assertEquals("Sun 4 Oct, 02:28", stamp(Instant.parse("2026-10-04T02:28:00Z"), TimeZone.UTC, now).resolve(environment))
            assertEquals("Sun 27 Sep 2015, 19:40", stamp(Instant.parse("2015-09-27T19:40:00Z"), TimeZone.UTC, now).resolve(environment))
        }

    @Test
    fun `a day header resolves to English words`() =
        runTest {
            val heading = dayHeading(LocalDate(2026, 10, 4), LocalDate(2026, 10, 4))

            assertEquals("Today", heading.title.resolve(environment))
            assertEquals("Sunday 4 October", heading.detail.resolve(environment))
        }

    @Test
    fun `a month and year resolves in full`() =
        runTest {
            assertEquals("March 2025", monthYear(Instant.parse("2025-03-02T10:00:00Z"), TimeZone.UTC).resolve(environment))
        }
}
