package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.text.UiText
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
import com.verdenroz.corvid.feature.mailbox.resources.custody_trackers_removed
import com.verdenroz.corvid.feature.mailbox.resources.custody_unsigned
import com.verdenroz.corvid.feature.mailbox.resources.custody_unsigned_detail

internal sealed interface CustodyCell {
    data class Sender(val value: UiText, val detail: UiText, val danger: Boolean) : CustodyCell

    data class WayIn(val value: UiText, val detail: UiText?) : CustodyCell

    data class Labelled(val labels: List<Label>, val attribution: UiText?, val guess: Label.Guess?) : CustodyCell
}

/** A message the milter never saw has no way-in cell: a missing header is not a clean message. */
internal fun custodyCells(
    provenance: Provenance,
    labels: List<Label>,
): List<CustodyCell> =
    listOfNotNull(
        senderCell(provenance.seal),
        provenance.rewrite?.let { wayInCell(it, provenance.suspect) },
        labelledCell(labels),
    )

private fun senderCell(seal: Seal): CustodyCell.Sender =
    when (seal) {
        is Seal.Signed ->
            CustodyCell.Sender(
                seal.signer?.let { uiText(Res.string.custody_signed_by, it) } ?: uiText(Res.string.custody_signed),
                uiText(Res.string.custody_dmarc_passed),
                danger = false,
            )
        is Seal.Failed -> {
            val claimed = seal.claimed
            val signedBy = seal.signedBy
            val detail =
                when {
                    claimed != null && signedBy != null -> uiText(Res.string.custody_claims_signed_by, claimed, signedBy)
                    claimed != null -> uiText(Res.string.custody_claims_unsigned, claimed)
                    else -> uiText(Res.string.custody_dmarc_failed)
                }
            CustodyCell.Sender(uiText(Res.string.custody_not_authenticated), detail, danger = true)
        }
        is Seal.Unsigned ->
            CustodyCell.Sender(
                uiText(Res.string.custody_unsigned),
                uiText(Res.string.custody_unsigned_detail),
                danger = false,
            )
    }

/** On a suspect message the detail line says its links are off, ahead of anything else it would say. */
private fun wayInCell(
    rewrite: Rewrite,
    suspect: Boolean,
): CustodyCell.WayIn {
    val links = uiText(Res.string.custody_links_disabled).takeIf { suspect }
    return when (rewrite) {
        is Rewrite.Clean -> CustodyCell.WayIn(uiText(Res.string.custody_no_trackers), links)
        is Rewrite.Skipped -> CustodyCell.WayIn(uiText(Res.string.custody_skipped), links ?: uiText(Res.string.custody_skipped_detail))
        is Rewrite.Stripped -> {
            val value =
                if (rewrite.stripped > 0) {
                    Plural(Res.plurals.custody_trackers_removed, rewrite.stripped, listOf(rewrite.stripped))
                } else {
                    uiText(Res.string.custody_no_trackers)
                }
            val proxied = rewrite.proxied.takeIf { it > 0 }?.let { Plural(Res.plurals.custody_images_proxied, it, listOf(it)) }
            CustodyCell.WayIn(value, links ?: proxied)
        }
    }
}

private fun labelledCell(labels: List<Label>): CustodyCell.Labelled {
    val guess = labels.filterIsInstance<Label.Guess>().firstOrNull()
    val attribution =
        when {
            guess != null -> uiText(Res.string.custody_guess)
            labels.isEmpty() -> null
            labels.all { it is Label.Rule } -> uiText(Res.string.custody_by_rule)
            labels.all { it is Label.Labeler } -> uiText(Res.string.custody_by_labeler)
            labels.all { it is Label.Yours } -> uiText(Res.string.custody_by_you)
            else -> null
        }
    return CustodyCell.Labelled(labels, attribution, guess)
}
