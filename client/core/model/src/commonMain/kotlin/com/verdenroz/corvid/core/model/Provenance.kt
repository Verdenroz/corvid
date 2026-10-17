package com.verdenroz.corvid.core.model

/** What the receiving server's own `Authentication-Results` says about who sent the message. */
sealed interface Seal {
    /** DMARC passed; [signer] is the authenticated From domain, when the verdict names it. */
    data class Signed(val signer: String?) : Seal

    /** DMARC failed: the From domain is [claimed], and [signedBy] is whoever did sign it, if anyone. */
    data class Failed(val claimed: String?, val signedBy: String?) : Seal

    /** No DMARC verdict either way, so nobody vouched for it. */
    data object Unsigned : Seal
}

/** What the tracker-stripping milter recorded in `X-Corvid-Rewrite`. */
sealed interface Rewrite {
    data object Clean : Rewrite

    data object Skipped : Rewrite

    /** [named] holds the stripped tracker hosts the header had room for; [omitted] counts the rest. */
    data class Stripped(
        val stripped: Int,
        val proxied: Int,
        val hosts: Int,
        val trackers: Int,
        val named: Set<String>,
        val omitted: Int,
    ) : Rewrite
}

/** Circles are people, rounded squares are sources. */
sealed interface SenderKind {
    data object Person : SenderKind

    data class Source(val kind: SourceKind) : SenderKind
}

enum class SourceKind { OwnServer, MailingList, Newsletter, Automated }

/** Everything the client can show about where a message came from; [unsubscribe] holds `List-Unsubscribe` URLs and [listId] the bare `List-Id`. */
data class Provenance(
    val seal: Seal,
    val rewrite: Rewrite?,
    val sender: SenderKind,
    val unsubscribe: List<String>,
    val listId: String? = null,
) {
    /** The list's bare id, only when DMARC passed: a header on unsigned mail is a claim anyone can make. */
    val trustedListId: String? get() = listId.takeIf { seal is Seal.Signed }

    /** DMARC failed, so the From line is a claim nobody vouched for. */
    val suspect: Boolean get() = seal is Seal.Failed

    /** Whether a link this message carries may be opened: never from a suspect message, and only on web or mail schemes. */
    fun canOpen(url: String): Boolean = !suspect && isOpenableUrl(url)
}

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")

/** The one allowlist of link schemes the client will hand to the system. */
fun isOpenableUrl(url: String): Boolean = url.contains(':') && url.substringBefore(':').lowercase() in OPENABLE_SCHEMES
