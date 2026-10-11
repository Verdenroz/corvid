package com.verdenroz.corvid.feature.compose

import androidx.compose.runtime.saveable.Saver
import com.verdenroz.corvid.core.data.Contact
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.text.UiText

/** Recipients are the raw comma-separated text of each field; [attachments] are already uploaded. */
data class ComposeForm(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    val attachments: List<Attachment> = emptyList(),
)

enum class RecipientField { To, Cc, Bcc }

fun ComposeForm.recipients(field: RecipientField): String =
    when (field) {
        RecipientField.To -> to
        RecipientField.Cc -> cc
        RecipientField.Bcc -> bcc
    }

fun ComposeForm.withRecipients(
    field: RecipientField,
    text: String,
): ComposeForm =
    when (field) {
        RecipientField.To -> copy(to = text)
        RecipientField.Cc -> copy(cc = text)
        RecipientField.Bcc -> copy(bcc = text)
    }

/** The contacts matching the address being typed in [field], [highlighted] being the one Enter or Tab accepts. */
data class Suggestions(
    val field: RecipientField,
    val contacts: List<Contact>,
    val highlighted: Int = 0,
)

/** [draftId] is the server draft the form was last saved as, if any. */
data class ComposeRestore(
    val form: ComposeForm,
    val draftId: String?,
)

private const val SAVED_FIELDS = 6
private const val SAVED_ATTACHMENT_FIELDS = 4

/** Six fields, then four per attachment: blob id, name, type and size. */
val ComposeRestoreSaver: Saver<ComposeRestore?, List<String>> =
    Saver(
        save = { restore ->
            restore?.let {
                listOf(it.form.to, it.form.cc, it.form.bcc, it.form.subject, it.form.body, it.draftId.orEmpty()) +
                    it.form.attachments.flatMap { file -> listOf(file.blobId, file.name.orEmpty(), file.type, file.size.toString()) }
            }
        },
        restore = { saved ->
            val attachments =
                saved.drop(SAVED_FIELDS).chunked(SAVED_ATTACHMENT_FIELDS).map { (blobId, name, type, size) ->
                    Attachment(blobId, name.ifEmpty { null }, type, size.toLong())
                }
            ComposeRestore(ComposeForm(saved[0], saved[1], saved[2], saved[3], saved[4], attachments), saved[5].ifEmpty { null })
        },
    )

sealed interface ComposeUiState {
    data object Loading : ComposeUiState

    data class Failed(val message: UiText) : ComposeUiState

    /**
     * [seed] keeps what the form does not show, such as reply threading. [stored] is the form the
     * server draft [draftId] holds, and the seed's own form while there is none. [error] is why the
     * last send did not go out. [saveFailed] while the form differs from the server's copy because
     * the last save was refused or never arrived. [confirmingDiscard] while a back press waits on
     * Discard or Keep editing. [suggestions] while a recipient field is being typed in and contacts match.
     * [uploading] names the files still on their way to the server, and
     * [attachError] is why the last one that failed was not attached.
     */
    data class Editing(
        val seed: Draft,
        val form: ComposeForm,
        val stored: ComposeForm,
        val draftId: String? = null,
        val saveFailed: Boolean = false,
        val sending: Boolean = false,
        val error: UiText? = null,
        val confirmingDiscard: Boolean = false,
        val suggestions: Suggestions? = null,
        val uploading: List<String> = emptyList(),
        val attachError: UiText? = null,
    ) : ComposeUiState

    data object Sent : ComposeUiState
}
