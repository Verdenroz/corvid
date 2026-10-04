package com.verdenroz.corvid.core.data

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** "Sun 4 Oct, 02:28", with the year after the month when it is not [now]'s year. */
fun stamp(
    instant: Instant,
    zone: TimeZone,
    now: Instant,
): String {
    val at = instant.toLocalDateTime(zone)
    val year = if (at.year == now.toLocalDateTime(zone).year) "" else " ${at.year}"
    return "${at.dayOfWeek.short()} ${at.day} ${at.month.short()}$year, ${at.hour.twoDigits()}:${at.minute.twoDigits()}"
}

internal fun DayOfWeek.short(): String = name.take(3).lowercase().replaceFirstChar { it.uppercase() }

internal fun Month.short(): String = name.take(3).lowercase().replaceFirstChar { it.uppercase() }

internal fun Int.twoDigits(): String = toString().padStart(2, '0')
