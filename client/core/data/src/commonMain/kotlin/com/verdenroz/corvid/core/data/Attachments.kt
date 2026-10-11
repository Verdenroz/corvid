package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.size_bytes
import com.verdenroz.corvid.core.text.resources.size_gigabytes
import com.verdenroz.corvid.core.text.resources.size_kilobytes
import com.verdenroz.corvid.core.text.resources.size_megabytes
import com.verdenroz.corvid.core.text.uiText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.pow
import kotlin.math.round

private const val UNKNOWN_TYPE = "application/octet-stream"
private const val UNIT = 1024.0
private const val MAX_BADGE = 4

/** A file the user chose to attach; [read] is called only once [size] is known to fit the server's upload limit. */
class PickedFile(
    val name: String,
    val type: String,
    val size: Long,
    val read: suspend () -> ByteArray,
)

/** Where a downloaded attachment went. */
sealed interface Handoff {
    /** Written to [location], where the user can find it again. */
    data class Saved(val location: String) : Handoff

    /** Passed to another app to open. */
    data object Opened : Handoff

    /** Nothing on this device opens the file's type. */
    data object NoViewer : Handoff

    /** The platform could not write the file; [reason] is its own message. */
    data class Failed(val reason: String) : Handoff
}

/** The platform's file picker and viewer; each platform app provides one. */
interface AttachmentFiles {
    /** The files the user chose, empty when they cancelled. */
    suspend fun pick(): List<PickedFile>

    suspend fun open(
        name: String,
        type: String,
        bytes: ByteArray,
    ): Handoff
}

sealed interface UploadResult {
    data class Uploaded(val attachment: Attachment) : UploadResult

    /** Over the server's advertised [limit] in bytes, so nothing was sent. */
    data class TooLarge(val limit: Long) : UploadResult
}

/** The name to save a file under when the sender gave none. */
fun Attachment.fileName(): String = name?.takeIf { it.isNotBlank() } ?: "attachment"

/** [name] cut down to a bare file name, so a sender's `../` or leading dot cannot steer where it is written. */
fun safeFileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl).trim().trimStart('.').ifBlank { "attachment" }

/** [name], or the first of `name (1).ext`, `name (2).ext` and on that [taken] does not claim. */
fun uniqueFileName(
    name: String,
    taken: (String) -> Boolean,
): String {
    if (!taken(name)) return name
    val base = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', "")
    return generateSequence(1) { it + 1 }
        .map { if (extension.isEmpty()) "$name ($it)" else "$base ($it).$extension" }
        .first { !taken(it) }
}

/** A short uppercase tag for the file's kind: its extension when it has a plausible one, else the type's subtype; null for neither. */
fun fileBadge(
    name: String?,
    type: String,
): String? {
    val extension = name?.substringAfterLast('.', "")?.takeIf { it.length in 1..MAX_BADGE && it.all(Char::isLetterOrDigit) }
    val subtype = type.substringAfter('/', "").substringBefore(';').takeIf { it.length in 1..MAX_BADGE && it.all(Char::isLetterOrDigit) }
    return (extension ?: subtype)?.uppercase()
}

/** Bytes in the largest unit that keeps the number under 1024, one decimal below ten. */
fun fileSize(bytes: Long): UiText {
    if (bytes < UNIT) return uiText(Res.string.size_bytes, bytes.toString())
    val units = listOf(Res.string.size_kilobytes, Res.string.size_megabytes, Res.string.size_gigabytes)
    val exponent = (1..units.size).last { bytes >= 1L shl (10 * it) }
    val value = bytes / UNIT.pow(exponent)
    val shown = if (value < 10) (round(value * 10) / 10).toString().removeSuffix(".0") else round(value).toLong().toString()
    return uiText(units[exponent - 1], shown)
}

/**
 * The server's `attachments`, less the inline images [html] draws by `cid:`. A part the sender
 * marked as an attachment is always kept, whatever its HTML references.
 */
internal fun JsonObject.attachmentList(html: String?): List<Attachment> =
    (get("attachments") as? JsonArray).orEmpty()
        .map { it.jsonObject }
        .filterNot { it.drawnInline(html) }
        .mapNotNull { part ->
            val blobId = part.text("blobId") ?: return@mapNotNull null
            Attachment(
                blobId = blobId,
                name = part.text("name"),
                type = part.text("type") ?: UNKNOWN_TYPE,
                size = part["size"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }

private fun JsonObject.drawnInline(html: String?): Boolean {
    val cid = text("cid")?.removeSurrounding("<", ">") ?: return false
    return text("disposition") != "attachment" && html != null && html.contains("cid:$cid", ignoreCase = true)
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
