package com.verdenroz.corvid.core.data.outbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why the server or the account refused a message, without the text it carried. */
@Serializable
enum class RejectionCategory {
    @SerialName("refused")
    Refused,

    @SerialName("forbidden")
    Forbidden,

    @SerialName("invalid")
    Invalid,

    @SerialName("alias_gone")
    AliasGone,
}

@Serializable
sealed interface OutboxState {
    @Serializable
    @SerialName("queued")
    data object Queued : OutboxState

    /** [recovering] is set when an earlier attempt may have reached the server, so nothing is created until a probe shows it did not. */
    @Serializable
    @SerialName("submitting")
    data class Submitting(val startedAt: Long, val recovering: Boolean = false) : OutboxState

    @Serializable
    @SerialName("unconfirmed")
    data object Unconfirmed : OutboxState

    @Serializable
    @SerialName("rejected")
    data class Rejected(val category: RejectionCategory) : OutboxState

    /** [tidy] is what is still to be cleaned off the server after the send, kept so a failed or interrupted cleanup is retried. */
    @Serializable
    @SerialName("sent")
    data class Sent(val serverEmailId: String, val at: Long, val tidy: Tidy? = null) : OutboxState
}

/** The send mark still on the sent copy and the draft this message replaced, each null once gone. */
@Serializable
data class Tidy(val keyword: String? = null, val replacesDraftId: String? = null)

/** What the plaintext state file says about one message; it holds no recipients, subject or body. */
data class OutboxItem(
    val id: String,
    val createdAt: Long,
    val attempt: Int,
    val keyFingerprint: String,
    val state: OutboxState,
)

/** A file the store refused to read, already renamed to `.bad` so it is not read again. */
sealed interface OutboxFault {
    val id: String

    data class Corrupt(override val id: String, val file: String) : OutboxFault

    data class ForeignSchema(override val id: String, val file: String, val found: String) : OutboxFault
}

class OutboxListing(val items: List<OutboxItem>, val faults: List<OutboxFault>)

/** A sealed message and the fingerprint of the key it was sealed to. */
class SealedMessage(val keyFingerprint: String, val bytes: ByteArray)

sealed interface MessageRead {
    class Found(val message: SealedMessage) : MessageRead

    data object Missing : MessageRead

    class Faulty(val fault: OutboxFault) : MessageRead
}
