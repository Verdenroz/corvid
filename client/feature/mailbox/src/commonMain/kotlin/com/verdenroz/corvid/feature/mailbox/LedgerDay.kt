package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.DayHeading
import com.verdenroz.corvid.core.data.dayHeading
import com.verdenroz.corvid.core.model.EmailSummary
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class LedgerDay(
    val date: LocalDate,
    val heading: DayHeading,
    val emails: List<EmailSummary>,
)

/** Groups a newest-first list into calendar days in [zone], keeping the list's own order. */
fun ledgerDays(
    emails: List<EmailSummary>,
    zone: TimeZone,
    today: LocalDate,
): List<LedgerDay> =
    emails
        .groupBy { it.receivedAt.toLocalDateTime(zone).date }
        .map { (date, onDay) -> LedgerDay(date, dayHeading(date, today), onDay) }
