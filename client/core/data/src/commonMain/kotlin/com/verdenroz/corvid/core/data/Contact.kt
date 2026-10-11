package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.EmailAddress
import kotlin.time.Instant

private val LOCAL_PUNCTUATION = Regex("[-_.+]")
private val UNANSWERED = listOf("noreply", "donotreply", "mailerdaemon")

/**
 * Someone in the mail sample: [sent] counts the messages the account sent them, [seen] the other
 * messages naming them as sender or recipient, and [lastSeen] is the newest of either.
 */
data class Contact(
    val address: EmailAddress,
    val sent: Int,
    val seen: Int,
    val lastSeen: Instant,
)

/**
 * Everyone the sample names except the account's own [identities] and addresses nobody reads,
 * people the account wrote to first, then by how often and how recently they appear. A draft
 * counts for nobody, since it was never sent. Names come from the newest message carrying one.
 */
internal fun rankContacts(
    emails: List<SampledEmail>,
    identities: Set<String>,
): List<Contact> {
    val own = identities.map { it.lowercase() }.toSet()
    val sightings =
        emails.filterNot { it.draft }.sortedByDescending { it.receivedAt }.flatMap { email ->
            val fromSelf = email.from.any { it.email.lowercase() in own }
            val named = if (fromSelf) email.to + email.cc else email.from + email.to + email.cc
            named.distinctBy { it.email.lowercase() }
                .filter { it.email.lowercase() !in own && it.answers() }
                .map { Sighting(it, fromSelf, email.receivedAt) }
        }
    return sightings.groupBy { it.address.email.lowercase() }.values.map { it.toContact() }.sortedWith(
        compareByDescending<Contact> { it.sent > 0 }
            .thenByDescending { it.sent + it.seen }
            .thenByDescending { it.lastSeen },
    )
}

/** Up to [limit] contacts whose name or address contains [fragment], those starting with it (or a word of the name) first. */
fun List<Contact>.matching(
    fragment: String,
    limit: Int,
): List<Contact> {
    val wanted = fragment.trim()
    if (wanted.isEmpty()) return emptyList()
    val found = filter { it.address.email.contains(wanted, true) || it.address.name?.contains(wanted, true) == true }
    val (prefixed, inside) = found.partition { it.startsWith(wanted) }
    return (prefixed + inside).take(limit)
}

private fun Contact.startsWith(fragment: String) =
    address.email.startsWith(fragment, true) ||
        address.name.orEmpty().split(' ').any { it.startsWith(fragment, true) }

private fun EmailAddress.answers(): Boolean {
    val local = email.substringBefore('@').lowercase().replace(LOCAL_PUNCTUATION, "")
    return UNANSWERED.none { it in local }
}

private class Sighting(val address: EmailAddress, val fromSelf: Boolean, val at: Instant)

/** [this] is newest first. */
private fun List<Sighting>.toContact(): Contact {
    val email = first().address.email
    val name = firstNotNullOfOrNull { sighting -> sighting.address.name?.trim()?.takeUnless { it.isEmpty() || it.equals(email, true) } }
    val sent = count { it.fromSelf }
    return Contact(EmailAddress(name, email), sent, size - sent, first().at)
}
