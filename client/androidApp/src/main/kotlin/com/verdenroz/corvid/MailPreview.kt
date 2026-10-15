package com.verdenroz.corvid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

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

/** Null for a body that is not a v1 payload this app reads: a missing field or another version. */
internal fun JsonObject.toMailPreview(): MailPreview? {
    val mail =
        try {
            pushJson.decodeFromJsonElement<PushedMail>(this)
        } catch (e: IllegalArgumentException) {
            return null
        }
    if (mail.v != 1) return null
    val sender = mail.from?.let { it.name?.takeIf(String::isNotBlank) ?: it.email }.orEmpty()
    return MailPreview(mail.id, sender, mail.subject, mail.preview)
}
