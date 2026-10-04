package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.heading_day_month
import com.verdenroz.corvid.core.text.resources.heading_day_month_year
import com.verdenroz.corvid.core.text.resources.heading_today
import com.verdenroz.corvid.core.text.resources.heading_weekday_day_month
import com.verdenroz.corvid.core.text.resources.stamp_other_year
import com.verdenroz.corvid.core.text.resources.stamp_this_year
import com.verdenroz.corvid.core.text.uiText
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
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

    private val today = LocalDate(2026, 10, 4)

    @Test
    fun `today's header says today and names the full date beside it`() {
        assertEquals(
            DayHeading(
                uiText(Res.string.heading_today),
                uiText(Res.string.heading_weekday_day_month, DayOfWeek.SUNDAY.long(), "4", Month.OCTOBER.long()),
            ),
            dayHeading(today, today),
        )
    }

    @Test
    fun `a day in the last week is headed by its weekday`() {
        assertEquals(
            DayHeading(DayOfWeek.MONDAY.long(), uiText(Res.string.heading_day_month, "28", Month.SEPTEMBER.long())),
            dayHeading(LocalDate(2026, 9, 28), today),
        )
    }

    @Test
    fun `a day a week or more ago is headed by its date`() {
        assertEquals(
            DayHeading(uiText(Res.string.heading_day_month, "27", Month.SEPTEMBER.short()), DayOfWeek.SUNDAY.long()),
            dayHeading(LocalDate(2026, 9, 27), today),
        )
    }

    @Test
    fun `a day in another year carries the year`() {
        assertEquals(
            DayHeading(uiText(Res.string.heading_day_month_year, "27", Month.SEPTEMBER.short(), "2015"), DayOfWeek.SUNDAY.long()),
            dayHeading(LocalDate(2015, 9, 27), today),
        )
    }
}
