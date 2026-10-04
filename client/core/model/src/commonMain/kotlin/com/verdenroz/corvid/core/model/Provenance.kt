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

/** Everything the client can show about where a message came from; [unsubscribe] holds `List-Unsubscribe` URLs. */
data class Provenance(
    val seal: Seal,
    val rewrite: Rewrite?,
    val sender: SenderKind,
    val unsubscribe: List<String>,
)
