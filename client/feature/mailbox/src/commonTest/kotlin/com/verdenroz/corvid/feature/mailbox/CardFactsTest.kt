package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.SENDER_WINDOW
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderWindow
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
    fun `a failed seal leaves the card with no filled primary action`() {
        assertEquals(false, writeIsPrimary(Seal.Failed("paypal.test", null)))
        assertEquals(true, writeIsPrimary(Seal.Signed("x.test")))
        assertEquals(true, writeIsPrimary(Seal.Unsigned))
    }
}
