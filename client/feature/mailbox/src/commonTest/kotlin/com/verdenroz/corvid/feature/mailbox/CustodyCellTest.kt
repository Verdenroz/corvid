package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.ThreadSummary
import com.verdenroz.corvid.core.text.UiText.Plural
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.custody_by_labeler
import com.verdenroz.corvid.feature.mailbox.resources.custody_by_rule
import com.verdenroz.corvid.feature.mailbox.resources.custody_by_you
import com.verdenroz.corvid.feature.mailbox.resources.custody_claims_signed_by
import com.verdenroz.corvid.feature.mailbox.resources.custody_claims_unsigned
import com.verdenroz.corvid.feature.mailbox.resources.custody_dmarc_failed
import com.verdenroz.corvid.feature.mailbox.resources.custody_dmarc_passed
import com.verdenroz.corvid.feature.mailbox.resources.custody_guess
import com.verdenroz.corvid.feature.mailbox.resources.custody_images_proxied
import com.verdenroz.corvid.feature.mailbox.resources.custody_links_disabled
import com.verdenroz.corvid.feature.mailbox.resources.custody_no_trackers
import com.verdenroz.corvid.feature.mailbox.resources.custody_not_authenticated
import com.verdenroz.corvid.feature.mailbox.resources.custody_signed
import com.verdenroz.corvid.feature.mailbox.resources.custody_signed_by
import com.verdenroz.corvid.feature.mailbox.resources.custody_skipped
import com.verdenroz.corvid.feature.mailbox.resources.custody_skipped_detail
import com.verdenroz.corvid.feature.mailbox.resources.custody_summarised_by
import com.verdenroz.corvid.feature.mailbox.resources.custody_trackers_removed
import com.verdenroz.corvid.feature.mailbox.resources.custody_unsigned
import com.verdenroz.corvid.feature.mailbox.resources.custody_unsigned_detail
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private fun cells(
    seal: Seal = Seal.Unsigned,
    rewrite: Rewrite? = Rewrite.Clean,
    labels: List<Label> = emptyList(),
) = custodyCells(Provenance(seal, rewrite, SenderKind.Person, unsubscribe = emptyList()), labels)

private fun sender(seal: Seal) = cells(seal = seal).first() as CustodyCell.Sender

private fun wayIn(
    rewrite: Rewrite,
    seal: Seal = Seal.Unsigned,
) = cells(seal = seal, rewrite = rewrite)[1] as CustodyCell.WayIn

private fun labelled(vararg labels: Label) = cells(labels = labels.toList()).last() as CustodyCell.Labelled

private fun stripped(
    stripped: Int,
    proxied: Int = 0,
) = Rewrite.Stripped(stripped, proxied, hosts = 1, trackers = stripped, named = emptySet(), omitted = 0)

class CustodyCellTest {
    @Test
    fun `a signed sender names the signer when the verdict does`() {
        assertEquals(
            CustodyCell.Sender(uiText(Res.string.custody_signed_by, "x.test"), uiText(Res.string.custody_dmarc_passed), danger = false),
            sender(Seal.Signed("x.test")),
        )
        assertEquals(uiText(Res.string.custody_signed), sender(Seal.Signed(null)).value)
    }

    @Test
    fun `a failed seal is danger and says whose name it claimed and who really signed`() {
        val both = sender(Seal.Failed("paypal.test", "paypa1.test"))
        assertEquals(true, both.danger)
        assertEquals(uiText(Res.string.custody_not_authenticated), both.value)
        assertEquals(uiText(Res.string.custody_claims_signed_by, "paypal.test", "paypa1.test"), both.detail)

        assertEquals(uiText(Res.string.custody_claims_unsigned, "paypal.test"), sender(Seal.Failed("paypal.test", null)).detail)
        assertEquals(uiText(Res.string.custody_dmarc_failed), sender(Seal.Failed(null, null)).detail)
    }

    @Test
    fun `an unsigned sender is plain, not danger`() {
        assertEquals(
            CustodyCell.Sender(uiText(Res.string.custody_unsigned), uiText(Res.string.custody_unsigned_detail), danger = false),
            sender(Seal.Unsigned),
        )
    }

    @Test
    fun `the way in reports clean, skipped and stripped mail as the milter recorded it`() {
        assertEquals(CustodyCell.WayIn(uiText(Res.string.custody_no_trackers), null), wayIn(Rewrite.Clean))
        assertEquals(
            CustodyCell.WayIn(uiText(Res.string.custody_skipped), uiText(Res.string.custody_skipped_detail)),
            wayIn(Rewrite.Skipped),
        )
        assertEquals(
            CustodyCell.WayIn(
                Plural(Res.plurals.custody_trackers_removed, 3, listOf(3)),
                Plural(Res.plurals.custody_images_proxied, 2, listOf(2)),
            ),
            wayIn(stripped(3, proxied = 2)),
        )
        assertEquals(CustodyCell.WayIn(uiText(Res.string.custody_no_trackers), null), wayIn(stripped(0)))
    }

    @Test
    fun `on a suspect message the way in says its links are off instead`() {
        val failed = Seal.Failed("paypal.test", null)
        val links = uiText(Res.string.custody_links_disabled)

        assertEquals(links, wayIn(Rewrite.Skipped, failed).detail)
        assertEquals(links, wayIn(stripped(3, proxied = 2), failed).detail)
        assertEquals(links, wayIn(Rewrite.Clean, failed).detail)
    }

    @Test
    fun `a message the milter never saw has no way-in cell rather than a clean one`() {
        val shown = cells(rewrite = null)

        assertEquals(1, shown.size)
        assertEquals(true, shown.none { it is CustodyCell.WayIn })
    }

    @Test
    fun `a message with no label and no guess has no labelled cell, so the sender spans the strip`() {
        assertEquals(listOf(CustodyCell.Sender::class), cells(rewrite = null).map { it::class })
        assertEquals(listOf(CustodyCell.Sender::class, CustodyCell.WayIn::class), cells().map { it::class })
        assertEquals(true, cells(labels = listOf(Label.Guess("bills"))).last() is CustodyCell.Labelled)
    }

    @Test
    fun `a summarised cell comes last, names the model and the local time, and exists only with a summary`() {
        val at = Instant.parse("2024-05-06T07:08:09Z")
        val provenance = Provenance(Seal.Unsigned, Rewrite.Clean, SenderKind.Person, unsubscribe = emptyList())
        val labels = listOf(Label.Yours("x"))

        fun last(model: String) = custodyCells(provenance, labels, ThreadSummary("t", at, model), TimeZone.UTC, at).last()

        val shown = stamp(at, TimeZone.UTC, at)
        assertEquals(CustodyCell.Summarised(uiText(Res.string.custody_summarised_by, "qwen3", shown)), last("qwen3"))
        assertEquals(CustodyCell.Summarised(shown), last(""))
        assertEquals(true, cells(labels = labels).none { it is CustodyCell.Summarised })
    }

    @Test
    fun `labels say who set them, and a guess is offered for teaching`() {
        val guess = Label.Guess("bills")
        assertEquals(
            CustodyCell.Labelled(listOf(guess, Label.Yours("x")), uiText(Res.string.custody_guess), guess),
            labelled(guess, Label.Yours("x")),
        )
        assertEquals(uiText(Res.string.custody_by_labeler), labelled(Label.Labeler("bills")).attribution)
        assertEquals(uiText(Res.string.custody_by_rule), labelled(Label.Rule("ops")).attribution)
        assertEquals(uiText(Res.string.custody_by_you), labelled(Label.Yours("tax")).attribution)
        assertNull(labelled(Label.Yours("tax"), Label.Rule("ops")).attribution)
        assertNull(labelled(Label.Labeler("bills")).guess)
    }
}
