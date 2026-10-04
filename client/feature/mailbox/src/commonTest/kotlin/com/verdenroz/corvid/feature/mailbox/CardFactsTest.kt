package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.SENDER_WINDOW
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.SenderWindow
import com.verdenroz.corvid.core.model.SourceKind
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.card_failed
import com.verdenroz.corvid.feature.mailbox.resources.card_failed_claims
import com.verdenroz.corvid.feature.mailbox.resources.card_no_trackers_of
import com.verdenroz.corvid.feature.mailbox.resources.card_signed_by_last
import com.verdenroz.corvid.feature.mailbox.resources.card_signed_of
import com.verdenroz.corvid.feature.mailbox.resources.card_trackers_last
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun window(
    size: Int,
    signed: Int = size,
    marked: Int = size,
    trackers: Int = 0,
) = SenderWindow(size, signed, marked, trackers)

class CardFactsTest {
    @Test
    fun `a window holding everything the sender sent counts all of it, singular for one`() {
        assertEquals(UiText.Plural(Res.plurals.card_signed_of, 1, listOf(1, 1)), authenticityFact(window(1), received = 1, signer = null))
    }

    @Test
    fun `a window cut at its cap says the last n`() {
        assertEquals(
            uiText(Res.string.card_signed_by_last, "github.com", 190, SENDER_WINDOW),
            authenticityFact(window(SENDER_WINDOW, signed = 190), received = 900, signer = "github.com"),
        )
        assertEquals(
            uiText(Res.string.card_trackers_last, 12, 150, SENDER_WINDOW),
            trackersFact(window(SENDER_WINDOW, marked = 150, trackers = 12), received = 900),
        )
    }

    @Test
    fun `a full window waits for the total before choosing its words`() {
        assertNull(authenticityFact(window(SENDER_WINDOW), received = null, signer = null))
        assertEquals(
            UiText.Plural(Res.plurals.card_signed_of, SENDER_WINDOW, listOf(SENDER_WINDOW, SENDER_WINDOW)),
            authenticityFact(window(SENDER_WINDOW), received = SENDER_WINDOW, signer = null),
        )
    }

    @Test
    fun `no trackers reads as none found, not zero`() {
        assertEquals(UiText.Plural(Res.plurals.card_no_trackers_of, 3, listOf(3)), trackersFact(window(3), received = 3))
    }

    @Test
    fun `a failed seal warns, naming the domain it claimed, and nothing else does`() {
        assertEquals(uiText(Res.string.card_failed_claims, "paypal.test"), failedWarning(Seal.Failed("paypal.test", "paypa1.test")))
        assertEquals(uiText(Res.string.card_failed), failedWarning(Seal.Failed(null, null)))
        assertNull(failedWarning(Seal.Signed("x.test")))
        assertNull(failedWarning(Seal.Unsigned))
    }

    @Test
    fun `a suspect source offers no unsubscribe at all`() {
        assertNull(unsubscribe(provenance(Seal.Failed("paypal.test", null), "https://paypal.test/u", "mailto:u@paypal.test")))
    }

    @Test
    fun `an unsubscribe on a script or file scheme is never offered`() {
        assertNull(unsubscribe(provenance(Seal.Unsigned, "javascript:alert(1)", "file:///etc/passwd", "JavaScript:x")))
    }

    @Test
    fun `a mailto unsubscribe composes and a web one opens`() {
        assertEquals(
            Unsubscribe.Mail(ComposeIntent.To("u@x.test", "stop")),
            unsubscribe(provenance(Seal.Signed("x.test"), "https://x.test/u", "mailto:u@x.test?subject=stop")),
        )
        assertEquals(Unsubscribe.Open("https://x.test/u"), unsubscribe(provenance(Seal.Unsigned, "javascript:x", "https://x.test/u")))
        assertEquals(Unsubscribe.Open("http://x.test/u"), unsubscribe(provenance(Seal.Unsigned, "http://x.test/u")))
    }
}

private fun provenance(
    seal: Seal,
    vararg unsubscribe: String,
) = Provenance(seal, rewrite = null, sender = SenderKind.Source(SourceKind.Newsletter), unsubscribe = unsubscribe.toList())
