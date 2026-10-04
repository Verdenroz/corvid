package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

private val NONE = SenderHeaders(listId = null, listUnsubscribe = emptyList(), autoSubmitted = null)

private fun kind(
    email: String,
    headers: SenderHeaders = NONE,
    ownDomain: String? = "nroz.test",
) = senderKind(EmailAddress(null, email), headers, ownDomain)

class SenderKindsTest {
    @Test
    fun `a plain message from an address is from a person`() {
        assertEquals(SenderKind.Person, kind("ada@analytical.test"))
    }

    @Test
    fun `a list id makes a mailing list`() {
        assertEquals(SenderKind.Source(SourceKind.MailingList), kind("ada@analytical.test", NONE.copy(listId = "<engine.analytical.test>")))
    }

    @Test
    fun `an unsubscribe link without a list id makes a newsletter`() {
        assertEquals(
            SenderKind.Source(SourceKind.Newsletter),
            kind("editors@browser.test", NONE.copy(listUnsubscribe = listOf("mailto:leave@browser.test"))),
        )
    }

    @Test
    fun `auto-submitted mail is a source unless it says no`() {
        assertEquals(SenderKind.Source(SourceKind.Automated), kind("billing@host.test", NONE.copy(autoSubmitted = "auto-generated")))
        assertEquals(SenderKind.Person, kind("billing@host.test", NONE.copy(autoSubmitted = "No")))
    }

    @Test
    fun `a no-reply style local part is a source`() {
        for (local in listOf(
            "noreply",
            "no-reply",
            "No_Reply",
            "donotreply",
            "do-not-reply",
            "mailer-daemon",
            "postmaster",
            "bounces+x123",
        )) {
            assertEquals(SenderKind.Source(SourceKind.Automated), kind("$local@service.test"), local)
        }
    }

    @Test
    fun `a local part that merely contains reply is a person`() {
        assertEquals(SenderKind.Person, kind("replyguy@forum.test"))
        assertEquals(SenderKind.Person, kind("noreplyfan@forum.test"))
    }

    @Test
    fun `a source on your own domain is your server`() {
        assertEquals(SenderKind.Source(SourceKind.OwnServer), kind("sentinel@NROZ.test", NONE.copy(autoSubmitted = "auto-generated")))
    }

    @Test
    fun `a person on your own domain is still a person`() {
        assertEquals(SenderKind.Person, kind("friend@nroz.test"))
    }

    @Test
    fun `a message with no from address is a person only without source headers`() {
        assertEquals(SenderKind.Person, senderKind(null, NONE, "nroz.test"))
        assertEquals(SenderKind.Source(SourceKind.MailingList), senderKind(null, NONE.copy(listId = "<l.test>"), "nroz.test"))
    }
}
