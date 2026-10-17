package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/** The server's digest of the mail since the last one. [DigestItem.summary] derives from mail, so it is untrusted. */
data class Digest(val id: String, val from: Instant, val to: Instant, val groups: List<DigestGroup>)

/** The items under one [label], or under none for the mail that fell in no group. */
data class DigestGroup(val label: String?, val items: List<DigestItem>)

data class DigestItem(
    val threadId: String,
    val emailId: String,
    val from: String,
    val subject: String,
    val receivedAt: Instant,
    val summary: String?,
    val fromName: String? = null,
    val isList: Boolean = false,
) {
    /** The sender's name, or the part of the address before the `@` when it signed none. */
    val displayName: String get() = fromName ?: from.substringBefore('@')
}

/** A list's item with the group label the server put it under. */
data class ListedItem(val item: DigestItem, val label: String?)

val Digest.items: List<DigestItem> get() = groups.flatMap { it.items }

/** The items that people wrote, which carry no `List-Id`. */
val Digest.people: List<DigestItem> get() = items.filterNot { it.isList }

/** The items that lists and services sent. */
val Digest.lists: List<ListedItem> get() = groups.flatMap { group -> group.items.filter { it.isList }.map { ListedItem(it, group.label) } }
