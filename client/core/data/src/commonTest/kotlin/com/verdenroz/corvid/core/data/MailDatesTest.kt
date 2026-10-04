package com.verdenroz.corvid.core.data

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MailDatesTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")

    @Test
    fun `a stamp this year reads as weekday, day, month and a 24-hour time`() {
        assertEquals("Sun 4 Oct, 02:28", stamp(Instant.parse("2026-10-04T02:28:00Z"), TimeZone.UTC, now))
    }

    @Test
    fun `a stamp from another year carries the year`() {
        assertEquals("Sun 27 Sep 2015, 19:40", stamp(Instant.parse("2015-09-27T19:40:00Z"), TimeZone.UTC, now))
    }

    @Test
    fun `a stamp is read in the given zone, not UTC`() {
        assertEquals("Sat 3 Oct, 22:28", stamp(Instant.parse("2026-10-04T02:28:00Z"), TimeZone.of("America/New_York"), now))
    }
}
