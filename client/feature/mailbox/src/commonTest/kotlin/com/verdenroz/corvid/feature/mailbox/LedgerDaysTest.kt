package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private fun email(
    id: String,
    at: String,
): Conversation {
    val provenance = Provenance(Seal.Unsigned, null, SenderKind.Person, emptyList())
    val latest = EmailSummary(id, id, "s", null, Instant.parse(at), emptySet(), "", provenance)
    return Conversation(latest, listOf(id), setOf(id))
}

class LedgerDaysTest {
    private val today = LocalDate(2026, 10, 4)

    @Test
    fun `conversations fall under the day of their latest message, newest day first`() {
        val days =
            ledgerDays(
                listOf(email("a", "2026-10-04T09:41:00Z"), email("b", "2026-10-04T02:28:00Z"), email("c", "2026-10-03T11:15:00Z")),
                TimeZone.UTC,
                today,
            )

        assertEquals(listOf(LocalDate(2026, 10, 4), LocalDate(2026, 10, 3)), days.map { it.date })
        assertEquals(listOf("a", "b"), days.first().conversations.map { it.latest.id })
    }

    @Test
    fun `the day boundary is local midnight, not UTC`() {
        val days = ledgerDays(listOf(email("late", "2026-10-04T02:28:00Z")), TimeZone.of("America/New_York"), today)

        assertEquals(LocalDate(2026, 10, 3), days.single().date)
    }
}
