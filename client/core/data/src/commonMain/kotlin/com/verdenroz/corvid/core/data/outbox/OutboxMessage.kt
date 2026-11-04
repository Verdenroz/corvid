package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything a send needs, fixed when it is queued. [messageId] and [keyword] name the message on
 * the server, so a retry can find what an earlier attempt created.
 */
data class OutboxMessage(
    val draft: Draft,
    val replacesDraftId: String?,
    val messageId: String,
    val keyword: String,
    val createdAt: Long,
)

@Serializable
private class AddressRecord(val name: String?, val email: String)

@Serializable
private class AttachmentRecord(val blobId: String, val name: String?, val type: String, val size: Long)

@Serializable
private class MessageRecord(
    val to: List<AddressRecord>,
    val cc: List<AddressRecord>,
    val bcc: List<AddressRecord>,
    val subject: String,
    val body: String,
    val inReplyTo: List<String>,
    val references: List<String>,
    val attachments: List<AttachmentRecord>,
    val from: String?,
    val replacesDraftId: String?,
    val messageId: String,
    val keyword: String,
    val createdAt: Long,
)

internal fun OutboxMessage.encode(): ByteArray =
    Json.encodeToString(
        MessageRecord.serializer(),
        MessageRecord(
            to = draft.to.map { AddressRecord(it.name, it.email) },
            cc = draft.cc.map { AddressRecord(it.name, it.email) },
            bcc = draft.bcc.map { AddressRecord(it.name, it.email) },
            subject = draft.subject,
            body = draft.body,
            inReplyTo = draft.inReplyTo,
            references = draft.references,
            attachments = draft.attachments.map { AttachmentRecord(it.blobId, it.name, it.type, it.size) },
            from = draft.from,
            replacesDraftId = replacesDraftId,
            messageId = messageId,
            keyword = keyword,
            createdAt = createdAt,
        ),
    ).toByteArray()

internal fun decodeOutboxMessage(bytes: ByteArray): OutboxMessage {
    val record = Json.decodeFromString(MessageRecord.serializer(), bytes.decodeToString())
    return OutboxMessage(
        draft =
            Draft(
                to = record.to.map { EmailAddress(it.name, it.email) },
                cc = record.cc.map { EmailAddress(it.name, it.email) },
                bcc = record.bcc.map { EmailAddress(it.name, it.email) },
                subject = record.subject,
                body = record.body,
                inReplyTo = record.inReplyTo,
                references = record.references,
                attachments = record.attachments.map { Attachment(it.blobId, it.name, it.type, it.size) },
                from = record.from,
            ),
        replacesDraftId = record.replacesDraftId,
        messageId = record.messageId,
        keyword = record.keyword,
        createdAt = record.createdAt,
    )
}
