package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.SourceKind
import kotlin.time.Instant

/**
 * Who wrote and what was labelled in the newest [window] messages outside Junk and Trash. Every number here is
 * counted over that window alone, never the whole mailbox; a sender's card counts the whole mailbox.
 */
data class Directory(
    val window: Int,
    val people: List<Person>,
    val sources: List<Source>,
    val labels: List<LabelCount>,
)

/**
 * A person: someone whose newest message in the window was typed rather than sent by a service, or whom the account
 * wrote to. [seal] is their newest message's, null when the window holds only mail the account sent them.
 */
data class Person(
    val address: EmailAddress,
    val received: Int,
    val sent: Int,
    val last: Instant,
    val seal: Seal?,
)

/** A service, list or the account's own server, by its newest message in the window; [marked] of [received] carried a tracker record. */
data class Source(
    val address: EmailAddress,
    val kind: SourceKind,
    val received: Int,
    val last: Instant,
    val seal: Seal,
    val marked: Int,
    val trackersStripped: Int,
)

/** How many messages in the window carry [keyword], which reads as [label]. */
data class LabelCount(
    val label: Label,
    val keyword: String,
    val messages: Int,
)

/** [emails] in any order; [identities] are the account's own addresses and [discarded] the ids of Junk and Trash. */
internal fun directory(
    emails: List<SampledEmail>,
    identities: Set<String>,
    discarded: Set<String>,
): Directory {
    val own = identities.map { it.lowercase() }.toSet()
    val kept = emails.filter { email -> email.mailboxIds.none { it in discarded } }.sortedByDescending { it.receivedAt }
    val (fromSelf, fromOthers) = kept.partition { email -> email.from.any { it.email.lowercase() in own } }
    val received = fromOthers.filter { it.from.isNotEmpty() }.groupBy { it.from.first().email.lowercase() }
    val written = writtenTo(fromSelf.filterNot { it.draft }, own)
    val sources = received.values.mapNotNull { it.toSource() }
    val sourceAddresses = sources.map { it.address.email.lowercase() }.toSet()
    val people =
        (received.keys + written.keys).filter { it !in sourceAddresses }.map { key ->
            person(received[key].orEmpty(), written[key].orEmpty())
        }
    return Directory(
        window = kept.size,
        people = people.sortedByDescending { it.last },
        sources = sources.sortedWith(compareByDescending<Source> { it.received }.thenByDescending { it.last }),
        labels = labelCounts(kept),
    )
}

/** Every recipient of the account's own mail, by address, each list newest first. */
private fun writtenTo(
    sent: List<SampledEmail>,
    own: Set<String>,
): Map<String, List<Pair<EmailAddress, SampledEmail>>> =
    sent.flatMap { email ->
        (email.to + email.cc).distinctBy { it.email.lowercase() }.filter { it.email.lowercase() !in own }.map { it to email }
    }.groupBy { (address, _) -> address.email.lowercase() }

/** [this] is newest first; null when the newest message came from a person. */
private fun List<SampledEmail>.toSource(): Source? {
    val newest = first()
    val kind = (newest.provenance.sender as? SenderKind.Source)?.kind ?: return null
    val rewrites = mapNotNull { it.provenance.rewrite }
    return Source(
        address = named(map { it.from.first() }),
        kind = kind,
        received = size,
        last = newest.receivedAt,
        seal = newest.provenance.seal,
        marked = rewrites.size,
        trackersStripped = rewrites.sumOf { (it as? Rewrite.Stripped)?.stripped ?: 0 },
    )
}

private fun person(
    received: List<SampledEmail>,
    written: List<Pair<EmailAddress, SampledEmail>>,
): Person {
    val newestIn = received.firstOrNull()
    val newestOut = written.firstOrNull()?.second
    return Person(
        address = named(received.map { it.from.first() } + written.map { it.first }),
        received = received.size,
        sent = written.size,
        last = listOfNotNull(newestIn?.receivedAt, newestOut?.receivedAt).max(),
        seal = newestIn?.provenance?.seal,
    )
}

/** The address with the first real name among [sightings], which are newest first. */
private fun named(sightings: List<EmailAddress>): EmailAddress {
    val email = sightings.first().email
    val name = sightings.firstNotNullOfOrNull { seen -> seen.name?.trim()?.takeUnless { it.isEmpty() || it.equals(email, true) } }
    return EmailAddress(name, email)
}

/** Each label in the window by its exact keyword, most used first. */
private fun labelCounts(emails: List<SampledEmail>): List<LabelCount> =
    emails.flatMap { email -> email.keywords.mapNotNull { keyword -> CorvidKeywords.labelOf(keyword)?.let { keyword to it } } }
        .groupBy({ it.first }, { it.second })
        .map { (keyword, labels) -> LabelCount(labels.first(), keyword, labels.size) }
        .sortedWith(compareByDescending<LabelCount> { it.messages }.thenBy { it.label.name }.thenBy { it.keyword })
