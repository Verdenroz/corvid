package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.SourceKind

private val NO_REPLY =
    Regex("^(no[-_.]?reply|do[-_.]?not[-_.]?reply|mailer[-_.]?daemon|postmaster|bounces?)([+._-].*)?$", RegexOption.IGNORE_CASE)

/** The headers that mark a message as sent by a service rather than typed by a person. */
data class SenderHeaders(
    val listId: String?,
    val listUnsubscribe: List<String>,
    val autoSubmitted: String?,
)

/**
 * A message is from a source if it carries `List-Id`, `List-Unsubscribe`, an `Auto-Submitted`
 * other than `no`, or a no-reply style local part; otherwise from a person. [ownDomain] only
 * names which kind of source it is.
 */
fun senderKind(
    from: EmailAddress?,
    headers: SenderHeaders,
    ownDomain: String?,
): SenderKind {
    val local = from?.email?.substringBeforeLast('@')
    val automated = headers.autoSubmitted?.trim()?.takeIf { it.isNotEmpty() && !it.equals("no", ignoreCase = true) } != null
    val noReply = local != null && NO_REPLY.matches(local)
    if (headers.listId == null && headers.listUnsubscribe.isEmpty() && !automated && !noReply) return SenderKind.Person
    val domain = from?.email?.substringAfterLast('@', "")
    return SenderKind.Source(
        when {
            ownDomain != null && domain.equals(ownDomain, ignoreCase = true) -> SourceKind.OwnServer
            headers.listId != null -> SourceKind.MailingList
            headers.listUnsubscribe.isNotEmpty() -> SourceKind.Newsletter
            else -> SourceKind.Automated
        },
    )
}
