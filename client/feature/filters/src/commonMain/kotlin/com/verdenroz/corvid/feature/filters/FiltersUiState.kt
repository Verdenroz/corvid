package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterRule

sealed interface FiltersUiState {
    data object Loading : FiltersUiState

    data class Failed(val message: String) : FiltersUiState

    /**
     * [rules] is what the editor holds, saved or not: a failed save leaves it as edited.
     * [foreignScript] names the active script that Corvid's would replace; [confirming]
     * is set while the user is asked to allow that.
     */
    data class Ready(
        val rules: List<FilterRule>,
        val folders: List<FilterAction.MoveToFolder>,
        val foreignScript: String? = null,
        val confirming: Boolean = false,
        val saving: Boolean = false,
        val error: String? = null,
    ) : FiltersUiState
}
