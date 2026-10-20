package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.reply_attribution
import com.verdenroz.corvid.core.text.uiText
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

fun replyAttribution(
    original: EmailDetail,
    zone: TimeZone,
    now: Instant,
): UiText =
    uiText(
        Res.string.reply_attribution,
        stamp(original.receivedAt, zone, now),
        UiText.FromServer(original.from?.toText().orEmpty()),
    )

fun replyDraft(
    original: EmailDetail,
    ownEmails: Set<String>,
    all: Boolean,
    attribution: String,
): Draft {
    val sender = listOfNotNull(original.from)
    val to = original.replyTo.ifEmpty { sender }
    val skipped = (to.map { it.email } + ownEmails).mapTo(HashSet()) { it.lowercase() }
    val others = original.to + original.cc + sender
    val cc = if (all) others.filterNot { it.email.lowercase() in skipped }.distinctBy { it.email.lowercase() } else emptyList()
    val quoted = original.body.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    return Draft(
        to = to,
        cc = cc,
        subject = withPrefix("Re", original.subject),
        body = "\n\n$attribution\n$quoted",
        inReplyTo = original.messageId,
        references = original.references + original.messageId,
    )
}

/**
 * [divider] is the resolved "Forwarded message" line; the header names are RFC 5322 field names, not prose.
 * The original's attachments go along by blob id, so nothing is downloaded or uploaded again.
 */
fun forwardDraft(
    original: EmailDetail,
    divider: String,
    date: String,
): Draft {
    val header =
        listOfNotNull(
            "From: ${original.from?.toText().orEmpty()}",
            "Date: $date",
            "Subject: ${original.subject}",
            "To: ${original.to.joinToString(", ") { it.toText() }}",
            original.cc.takeIf { it.isNotEmpty() }?.let { "Cc: ${it.joinToString(", ") { address -> address.toText() }}" },
        )
    return Draft(
        subject = withPrefix("Fwd", original.subject),
        body = "\n\n$divider\n${header.joinToString("\n")}\n\n${original.body}",
        attachments = original.attachments,
    )
}

private fun withPrefix(
    prefix: String,
    subject: String,
): String = if (subject.trimStart().startsWith("$prefix:", ignoreCase = true)) subject else "$prefix: $subject"
