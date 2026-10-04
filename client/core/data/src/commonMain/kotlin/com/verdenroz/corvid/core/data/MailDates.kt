package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.clock_time
import com.verdenroz.corvid.core.text.resources.day_long_friday
import com.verdenroz.corvid.core.text.resources.day_long_monday
import com.verdenroz.corvid.core.text.resources.day_long_saturday
import com.verdenroz.corvid.core.text.resources.day_long_sunday
import com.verdenroz.corvid.core.text.resources.day_long_thursday
import com.verdenroz.corvid.core.text.resources.day_long_tuesday
import com.verdenroz.corvid.core.text.resources.day_long_wednesday
import com.verdenroz.corvid.core.text.resources.day_short_friday
import com.verdenroz.corvid.core.text.resources.day_short_monday
import com.verdenroz.corvid.core.text.resources.day_short_saturday
import com.verdenroz.corvid.core.text.resources.day_short_sunday
import com.verdenroz.corvid.core.text.resources.day_short_thursday
import com.verdenroz.corvid.core.text.resources.day_short_tuesday
import com.verdenroz.corvid.core.text.resources.day_short_wednesday
import com.verdenroz.corvid.core.text.resources.heading_day_month
import com.verdenroz.corvid.core.text.resources.heading_day_month_year
import com.verdenroz.corvid.core.text.resources.heading_today
import com.verdenroz.corvid.core.text.resources.heading_weekday_day_month
import com.verdenroz.corvid.core.text.resources.month_long_april
import com.verdenroz.corvid.core.text.resources.month_long_august
import com.verdenroz.corvid.core.text.resources.month_long_december
import com.verdenroz.corvid.core.text.resources.month_long_february
import com.verdenroz.corvid.core.text.resources.month_long_january
import com.verdenroz.corvid.core.text.resources.month_long_july
import com.verdenroz.corvid.core.text.resources.month_long_june
import com.verdenroz.corvid.core.text.resources.month_long_march
import com.verdenroz.corvid.core.text.resources.month_long_may
import com.verdenroz.corvid.core.text.resources.month_long_november
import com.verdenroz.corvid.core.text.resources.month_long_october
import com.verdenroz.corvid.core.text.resources.month_long_september
import com.verdenroz.corvid.core.text.resources.month_short_april
import com.verdenroz.corvid.core.text.resources.month_short_august
import com.verdenroz.corvid.core.text.resources.month_short_december
import com.verdenroz.corvid.core.text.resources.month_short_february
import com.verdenroz.corvid.core.text.resources.month_short_january
import com.verdenroz.corvid.core.text.resources.month_short_july
import com.verdenroz.corvid.core.text.resources.month_short_june
import com.verdenroz.corvid.core.text.resources.month_short_march
import com.verdenroz.corvid.core.text.resources.month_short_may
import com.verdenroz.corvid.core.text.resources.month_short_november
import com.verdenroz.corvid.core.text.resources.month_short_october
import com.verdenroz.corvid.core.text.resources.month_short_september
import com.verdenroz.corvid.core.text.resources.month_year
import com.verdenroz.corvid.core.text.resources.stamp_other_year
import com.verdenroz.corvid.core.text.resources.stamp_this_year
import com.verdenroz.corvid.core.text.uiText
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** "Sun 4 Oct, 02:28" in [zone], with the year after the month when it is not [now]'s year. */
fun stamp(
    instant: Instant,
    zone: TimeZone,
    now: Instant,
): UiText {
    val at = instant.toLocalDateTime(zone)
    val time = clockTime(at.hour, at.minute)
    return if (at.year == now.toLocalDateTime(zone).year) {
        uiText(Res.string.stamp_this_year, at.dayOfWeek.short(), at.day.toString(), at.month.short(), time)
    } else {
        uiText(Res.string.stamp_other_year, at.dayOfWeek.short(), at.day.toString(), at.month.short(), at.year.toString(), time)
    }
}

fun clockTime(
    hour: Int,
    minute: Int,
): UiText = uiText(Res.string.clock_time, hour.twoDigits(), minute.twoDigits())

fun clockTime(
    instant: Instant,
    zone: TimeZone,
): UiText = instant.toLocalDateTime(zone).let { clockTime(it.hour, it.minute) }

/** "March 2025", for how long a sender has been writing. */
fun monthYear(
    instant: Instant,
    zone: TimeZone,
): UiText {
    val at = instant.toLocalDateTime(zone)
    return uiText(Res.string.month_year, at.month.long(), at.year.toString())
}

data class DayHeading(
    val title: UiText,
    val detail: UiText,
)

/** Today by name, the last six days by weekday, anything older by date. */
fun dayHeading(
    day: LocalDate,
    today: LocalDate,
): DayHeading =
    when {
        day == today ->
            DayHeading(
                uiText(Res.string.heading_today),
                uiText(Res.string.heading_weekday_day_month, day.dayOfWeek.long(), day.day.toString(), day.month.long()),
            )
        day > today.minus(DatePeriod(days = 7)) ->
            DayHeading(
                day.dayOfWeek.long(),
                uiText(Res.string.heading_day_month, day.day.toString(), day.month.long()),
            )
        day.year == today.year ->
            DayHeading(
                uiText(Res.string.heading_day_month, day.day.toString(), day.month.short()),
                day.dayOfWeek.long(),
            )
        else ->
            DayHeading(
                uiText(Res.string.heading_day_month_year, day.day.toString(), day.month.short(), day.year.toString()),
                day.dayOfWeek.long(),
            )
    }

internal fun DayOfWeek.long(): UiText =
    uiText(
        when (this) {
            DayOfWeek.MONDAY -> Res.string.day_long_monday
            DayOfWeek.TUESDAY -> Res.string.day_long_tuesday
            DayOfWeek.WEDNESDAY -> Res.string.day_long_wednesday
            DayOfWeek.THURSDAY -> Res.string.day_long_thursday
            DayOfWeek.FRIDAY -> Res.string.day_long_friday
            DayOfWeek.SATURDAY -> Res.string.day_long_saturday
            DayOfWeek.SUNDAY -> Res.string.day_long_sunday
        },
    )

internal fun Month.long(): UiText =
    uiText(
        when (this) {
            Month.JANUARY -> Res.string.month_long_january
            Month.FEBRUARY -> Res.string.month_long_february
            Month.MARCH -> Res.string.month_long_march
            Month.APRIL -> Res.string.month_long_april
            Month.MAY -> Res.string.month_long_may
            Month.JUNE -> Res.string.month_long_june
            Month.JULY -> Res.string.month_long_july
            Month.AUGUST -> Res.string.month_long_august
            Month.SEPTEMBER -> Res.string.month_long_september
            Month.OCTOBER -> Res.string.month_long_october
            Month.NOVEMBER -> Res.string.month_long_november
            Month.DECEMBER -> Res.string.month_long_december
        },
    )

internal fun DayOfWeek.short(): UiText =
    uiText(
        when (this) {
            DayOfWeek.MONDAY -> Res.string.day_short_monday
            DayOfWeek.TUESDAY -> Res.string.day_short_tuesday
            DayOfWeek.WEDNESDAY -> Res.string.day_short_wednesday
            DayOfWeek.THURSDAY -> Res.string.day_short_thursday
            DayOfWeek.FRIDAY -> Res.string.day_short_friday
            DayOfWeek.SATURDAY -> Res.string.day_short_saturday
            DayOfWeek.SUNDAY -> Res.string.day_short_sunday
        },
    )

internal fun Month.short(): UiText =
    uiText(
        when (this) {
            Month.JANUARY -> Res.string.month_short_january
            Month.FEBRUARY -> Res.string.month_short_february
            Month.MARCH -> Res.string.month_short_march
            Month.APRIL -> Res.string.month_short_april
            Month.MAY -> Res.string.month_short_may
            Month.JUNE -> Res.string.month_short_june
            Month.JULY -> Res.string.month_short_july
            Month.AUGUST -> Res.string.month_short_august
            Month.SEPTEMBER -> Res.string.month_short_september
            Month.OCTOBER -> Res.string.month_short_october
            Month.NOVEMBER -> Res.string.month_short_november
            Month.DECEMBER -> Res.string.month_short_december
        },
    )

private fun Int.twoDigits(): String = toString().padStart(2, '0')
