package com.verdenroz.corvid.feature.compose

import com.verdenroz.corvid.core.model.Draft

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

    data class Failed(val message: String) : ComposeUiState

    /** [seed] keeps what the form does not show, such as reply threading. [error] is why the last send did not go out. */
    data class Editing(
        val seed: Draft,
        val form: ComposeForm,
        val sending: Boolean = false,
        val error: String? = null,
    ) : ComposeUiState

    data object Sent : ComposeUiState
}
