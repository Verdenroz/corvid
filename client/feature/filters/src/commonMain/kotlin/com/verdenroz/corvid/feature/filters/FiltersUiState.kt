package com.verdenroz.corvid.feature.filters

import androidx.compose.runtime.saveable.Saver
import com.verdenroz.corvid.core.data.decodeRule
import com.verdenroz.corvid.core.data.encodeRule
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.text.UiText

sealed interface FiltersUiState {
    data object Loading : FiltersUiState

    data class Failed(val message: UiText) : FiltersUiState

    /** [rules] is what the editor holds, saved or not: a failed save leaves it as edited. */
    data class Ready(
        val rules: List<FilterRule>,
        val folders: List<MailboxRef>,
        val foreignScript: String? = null,
        val editing: RuleDraft? = null,
        val confirmingDiscard: Boolean = false,
        val save: SaveStatus = SaveStatus.Idle,
        val suggestions: List<RuleSuggestion> = emptyList(),
        val confirmingSuggestion: RuleSuggestion? = null,
    ) : FiltersUiState
}

/** A rule being typed into the editor; a null [index] is a rule not yet in the list. */
data class RuleDraft(val index: Int?, val rule: FilterRule)

val RuleDraftSaver: Saver<RuleDraft?, List<String>> =
    Saver(
        save = { draft -> draft?.let { listOf(it.index?.toString().orEmpty(), encodeRule(it.rule)) } },
        restore = { saved -> RuleDraft(saved[0].toIntOrNull(), decodeRule(saved[1])) },
    )

sealed interface SaveStatus {
    data object Idle : SaveStatus

    data object Saving : SaveStatus

    /** Waiting for the user to allow replacing the active script. */
    data object Confirming : SaveStatus

    data class Failed(val message: UiText) : SaveStatus
}
