package com.verdenroz.corvid.feature.compose

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

sealed interface ComposeUiState {
    data object Loading : ComposeUiState

    data class Failed(val message: UiText) : ComposeUiState

    /**
     * [seed] keeps what the form does not show, such as reply threading. [error] is why the last
     * send did not go out. [confirmingDiscard] while a back press waits on Discard or Keep editing.
     */
    data class Editing(
        val seed: Draft,
        val form: ComposeForm,
        val sending: Boolean = false,
        val error: UiText? = null,
        val confirmingDiscard: Boolean = false,
    ) : ComposeUiState

    data object Sent : ComposeUiState
}
