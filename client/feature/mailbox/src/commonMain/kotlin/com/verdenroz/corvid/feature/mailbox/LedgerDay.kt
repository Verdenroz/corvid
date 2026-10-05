package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.DayHeading
import com.verdenroz.corvid.core.data.dayHeading
import com.verdenroz.corvid.core.model.Conversation
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class LedgerDay(
    val date: LocalDate,
    val heading: DayHeading,
    val conversations: List<Conversation>,
)

fun ledgerDays(
    conversations: List<Conversation>,
    zone: TimeZone,
    today: LocalDate,
): List<LedgerDay> =
    conversations
        .groupBy { it.latest.receivedAt.toLocalDateTime(zone).date }
        .map { (date, onDay) -> LedgerDay(date, dayHeading(date, today), onDay) }
