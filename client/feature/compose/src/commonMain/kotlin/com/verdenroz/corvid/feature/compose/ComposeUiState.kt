package com.verdenroz.corvid.feature.compose

import androidx.compose.runtime.saveable.Saver
import com.verdenroz.corvid.core.data.Contact
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.text.UiText

/** Recipients are the raw comma-separated text of each field. */
data class ComposeForm(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
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

val ComposeRestoreSaver: Saver<ComposeRestore?, List<String>> =
    Saver(
        save = { restore ->
            restore?.let { listOf(it.form.to, it.form.cc, it.form.bcc, it.form.subject, it.form.body, it.draftId.orEmpty()) }
        },
        restore = { saved ->
            ComposeRestore(ComposeForm(saved[0], saved[1], saved[2], saved[3], saved[4]), saved[5].ifEmpty { null })
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
    ) : ComposeUiState

    data object Sent : ComposeUiState
}
