package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.SENDER_WINDOW
import com.verdenroz.corvid.core.data.mailtoIntent
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderWindow
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.UiText.Plural
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.card_failed
import com.verdenroz.corvid.feature.mailbox.resources.card_failed_claims
import com.verdenroz.corvid.feature.mailbox.resources.card_no_tracker_record
import com.verdenroz.corvid.feature.mailbox.resources.card_no_trackers_last
import com.verdenroz.corvid.feature.mailbox.resources.card_no_trackers_of
import com.verdenroz.corvid.feature.mailbox.resources.card_signed_by_last
import com.verdenroz.corvid.feature.mailbox.resources.card_signed_by_of
import com.verdenroz.corvid.feature.mailbox.resources.card_signed_last
import com.verdenroz.corvid.feature.mailbox.resources.card_signed_of
import com.verdenroz.corvid.feature.mailbox.resources.card_trackers_last
import com.verdenroz.corvid.feature.mailbox.resources.card_trackers_of

/**
 * Whether the window stopped short of everything the sender sent. A full window with the total not
 * yet known could be either, so it is null and the fact waits for the count.
 */
internal fun capped(
    window: SenderWindow,
    received: Int?,
): Boolean? =
    when {
        window.size < SENDER_WINDOW -> false
        received == null -> null
        else -> received > window.size
    }

internal fun authenticityFact(
    window: SenderWindow,
    received: Int?,
    signer: String?,
): UiText? =
    when (capped(window, received)) {
        null -> null
        true ->
            if (signer == null) {
                uiText(
                    Res.string.card_signed_last,
                    window.signed,
                    window.size,
                )
            } else {
                uiText(Res.string.card_signed_by_last, signer, window.signed, window.size)
            }
        false ->
            if (signer == null) {
                Plural(Res.plurals.card_signed_of, window.size, listOf(window.signed, window.size))
            } else {
                Plural(Res.plurals.card_signed_by_of, window.size, listOf(signer, window.signed, window.size))
            }
    }

internal fun trackersFact(
    window: SenderWindow,
    received: Int?,
): UiText? {
    val last = capped(window, received) ?: return null
    return when {
        window.marked == 0 -> uiText(Res.string.card_no_tracker_record)
        window.trackersStripped == 0 && last -> uiText(Res.string.card_no_trackers_last, window.marked, window.size)
        window.trackersStripped == 0 -> Plural(Res.plurals.card_no_trackers_of, window.marked, listOf(window.marked))
        last -> uiText(Res.string.card_trackers_last, window.trackersStripped, window.marked, window.size)
        else -> Plural(Res.plurals.card_trackers_of, window.marked, listOf(window.trackersStripped, window.marked))
    }
}

internal sealed interface Unsubscribe {
    data class Mail(val intent: ComposeIntent.To) : Unsubscribe

    data class Open(val url: String) : Unsubscribe
}

/** A mailto offer composes and any other offer opens; a suspect source offers nothing. */
internal fun unsubscribe(provenance: Provenance): Unsubscribe? {
    val offered = provenance.unsubscribe.filter(provenance::canOpen)
    return offered.firstNotNullOfOrNull(::mailtoIntent)?.let(Unsubscribe::Mail) ?: offered.firstOrNull()?.let(Unsubscribe::Open)
}

/** A sender whose seal failed gets a warning instead of a filled Write, so the card does not nudge a reply to a spoofer. */
internal fun failedWarning(seal: Seal?): UiText? =
    (seal as? Seal.Failed)?.let {
            failed ->
        failed.claimed?.let { uiText(Res.string.card_failed_claims, it) } ?: uiText(Res.string.card_failed)
    }
