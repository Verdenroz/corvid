package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailDetail

fun replyDraft(
    original: EmailDetail,
    ownEmail: String,
    all: Boolean,
): Draft {
    val sender = listOfNotNull(original.from)
    val to = original.replyTo.ifEmpty { sender }
    val skipped = (to.map { it.email } + ownEmail).map { it.lowercase() }
    val others = original.to + original.cc + sender
    val cc = if (all) others.filterNot { it.email.lowercase() in skipped }.distinctBy { it.email.lowercase() } else emptyList()
    val quoted = original.body.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    return Draft(
        to = to,
        cc = cc,
        subject = withPrefix("Re", original.subject),
        body = "\n\nOn ${original.receivedAt}, ${original.from?.toText().orEmpty()} wrote:\n$quoted",
        inReplyTo = original.messageId,
        references = original.references + original.messageId,
    )
}

fun forwardDraft(original: EmailDetail): Draft {
    val header =
        listOfNotNull(
            "From: ${original.from?.toText().orEmpty()}",
            "Date: ${original.receivedAt}",
            "Subject: ${original.subject}",
            "To: ${original.to.joinToString(", ") { it.toText() }}",
            original.cc.takeIf { it.isNotEmpty() }?.let { "Cc: ${it.joinToString(", ") { address -> address.toText() }}" },
        )
    return Draft(
        subject = withPrefix("Fwd", original.subject),
        body = "\n\n---------- Forwarded message ----------\n${header.joinToString("\n")}\n\n${original.body}",
    )
}

private fun withPrefix(
    prefix: String,
    subject: String,
): String = if (subject.trimStart().startsWith("$prefix:", ignoreCase = true)) subject else "$prefix: $subject"
