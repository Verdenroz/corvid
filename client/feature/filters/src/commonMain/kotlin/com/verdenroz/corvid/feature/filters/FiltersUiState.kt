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
        val confirming: Boolean = false,
        val saving: Boolean = false,
        val error: String? = null,
    ) : FiltersUiState
}
