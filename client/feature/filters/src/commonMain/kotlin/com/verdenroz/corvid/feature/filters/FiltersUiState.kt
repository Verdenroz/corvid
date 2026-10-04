package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef

sealed interface FiltersUiState {
    data object Loading : FiltersUiState

    data class Failed(val message: String) : FiltersUiState

    /** [rules] is what the editor holds, saved or not: a failed save leaves it as edited. */
    data class Ready(
        val rules: List<FilterRule>,
        val folders: List<MailboxRef>,
        val foreignScript: String? = null,
        val editing: RuleDraft? = null,
        val save: SaveStatus = SaveStatus.Idle,
    ) : FiltersUiState
}

/** A rule being typed into the editor; a null [index] is a rule not yet in the list. */
data class RuleDraft(val index: Int?, val rule: FilterRule)

sealed interface SaveStatus {
    data object Idle : SaveStatus

    data object Saving : SaveStatus

    /** Waiting for the user to allow replacing the active script. */
    data object Confirming : SaveStatus

    data class Failed(val message: String) : SaveStatus
}
