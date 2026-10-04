package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.stamp_other_year
import com.verdenroz.corvid.core.text.resources.stamp_this_year
import com.verdenroz.corvid.core.text.uiText
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MailDatesTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")

    @Test
    fun `a stamp this year reads as weekday, day, month and a 24-hour time`() {
        assertEquals(
            uiText(Res.string.stamp_this_year, DayOfWeek.SUNDAY.short(), "4", Month.OCTOBER.short(), clockTime(2, 28)),
            stamp(Instant.parse("2026-10-04T02:28:00Z"), TimeZone.UTC, now),
        )
    }

    @Test
    fun `a stamp from another year carries the year`() {
        assertEquals(
            uiText(Res.string.stamp_other_year, DayOfWeek.SUNDAY.short(), "27", Month.SEPTEMBER.short(), "2015", clockTime(19, 40)),
            stamp(Instant.parse("2015-09-27T19:40:00Z"), TimeZone.UTC, now),
        )
    }

    @Test
    fun `a stamp is read in the given zone, not UTC`() {
        assertEquals(
            uiText(Res.string.stamp_this_year, DayOfWeek.SATURDAY.short(), "3", Month.OCTOBER.short(), clockTime(22, 28)),
            stamp(Instant.parse("2026-10-04T02:28:00Z"), TimeZone.of("America/New_York"), now),
        )
    }
}
