package com.verdenroz.corvid.feature.compose

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

    /** [error] is why the last send did not go out; [form] is untouched by it. */
    data class Editing(
        val form: ComposeForm,
        val inReplyTo: List<String> = emptyList(),
        val references: List<String> = emptyList(),
        val sending: Boolean = false,
        val error: String? = null,
    ) : ComposeUiState

    data object Sent : ComposeUiState
}
