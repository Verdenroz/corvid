package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailDetail

fun replyDraft(
    original: EmailDetail,
    ownEmail: String,
    all: Boolean,
): Draft {
    val to = original.replyTo.ifEmpty { listOf(original.from) }
    val skipped = (to + ownEmail).map { emailOf(it).lowercase() }
    val others = original.to + original.cc + original.from
    val cc = if (all) others.filterNot { emailOf(it).lowercase() in skipped }.distinct() else emptyList()
    val quoted = original.body.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    return Draft(
        to = to,
        cc = cc,
        subject = withPrefix("Re", original.subject),
        body = "\n\nOn ${original.receivedAt}, ${original.from} wrote:\n$quoted",
        inReplyTo = original.messageId,
        references = original.references + original.messageId,
    )
}

fun forwardDraft(original: EmailDetail): Draft {
    val header =
        listOfNotNull(
            "From: ${original.from}",
            "Date: ${original.receivedAt}",
            "Subject: ${original.subject}",
            "To: ${original.to.joinToString(", ")}",
            original.cc.takeIf { it.isNotEmpty() }?.let { "Cc: ${it.joinToString(", ")}" },
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
