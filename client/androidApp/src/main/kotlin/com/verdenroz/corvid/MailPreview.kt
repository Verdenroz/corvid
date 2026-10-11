package com.verdenroz.corvid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class PushedSender(val name: String? = null, val email: String)

@Serializable
private class PushedMail(
    val v: Int,
    val id: String,
    val from: PushedSender? = null,
    val subject: String = "",
    val preview: String = "",
)

private val pushJson = Json { ignoreUnknownKeys = true }

/** One new message as the server pushes it; [title] is the sender's name, or the address where there is none, or blank. */
data class MailPreview(
    val id: String,
    val title: String,
    val subject: String,
    val preview: String,
) {
    fun text(noSubject: String): String = subject.ifBlank { noSubject }

    fun bigText(noSubject: String): String = listOf(text(noSubject), preview).filter { it.isNotBlank() }.joinToString("\n")
}

/** What one push asks the phone to show. */
sealed interface PushNotice {
    /** Decrypted, so from the server, but not a payload this app reads: a newer version, say. */
    data object Generic : PushNotice

    data class Mail(val preview: MailPreview) : PushNotice
}

/**
 * Null, so nothing is posted, for a body the connector could not decrypt: it hands one over as is, and anyone who
 * can reach the push host can post to the endpoint.
 */
fun pushNotice(
    content: ByteArray,
    decrypted: Boolean,
): PushNotice? {
    if (!decrypted) return null
    val mail =
        try {
            pushJson.decodeFromString<PushedMail>(content.decodeToString())
        } catch (e: IllegalArgumentException) {
            return PushNotice.Generic
        }
    if (mail.v != 1) return PushNotice.Generic
    val sender = mail.from?.let { it.name?.takeIf(String::isNotBlank) ?: it.email }.orEmpty()
    return PushNotice.Mail(MailPreview(mail.id, sender, mail.subject, mail.preview))
}
