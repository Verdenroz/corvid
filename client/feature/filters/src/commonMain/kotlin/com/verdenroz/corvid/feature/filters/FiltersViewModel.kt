package com.verdenroz.corvid.feature.filters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.FiltersFormatException
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.SaveResult
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_another_active
import com.verdenroz.corvid.feature.filters.resources.filters_newer
import com.verdenroz.corvid.feature.filters.resources.filters_not_saved
import com.verdenroz.corvid.feature.filters.resources.filters_unreadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FiltersViewModel(
    private val repository: FiltersRepository,
    private val restored: RuleDraft? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<FiltersUiState>(FiltersUiState.Loading)
    val state: StateFlow<FiltersUiState> = _state

    init {
        viewModelScope.launch { load() }
    }

    /** A null [index] starts a new rule. */
    fun startEdit(index: Int?) = update { it.copy(editing = RuleDraft(index, index?.let { i -> it.rules[i] } ?: FilterRule.blank())) }

    fun changeDraft(rule: FilterRule) = update { it.copy(editing = it.editing?.copy(rule = rule)) }

    fun cancelEdit() = update { it.copy(editing = null, confirmingDiscard = false) }

    /**
     * Back while the editor is open: an untouched draft closes, a changed one asks first, and a
     * second back discards it. False when no editor is open, so back leaves the screen.
     */
    fun back(): Boolean {
        val ready = _state.value as? FiltersUiState.Ready ?: return false
        val draft = ready.editing ?: return false
        val untouched = draft.rule == (draft.index?.let { ready.rules.getOrNull(it) } ?: FilterRule.blank())
        if (ready.confirmingDiscard || untouched) cancelEdit() else update { it.copy(confirmingDiscard = true) }
        return true
    }

    fun keepEditing() = update { it.copy(confirmingDiscard = false) }

    fun saveDraft() {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        val draft = ready.editing ?: return
        if (ready.save is SaveStatus.Saving) return
        update { it.copy(editing = null, confirmingDiscard = false) }
        persist(
            if (draft.index == null) ready.rules + draft.rule else ready.rules.replaced(draft.index, draft.rule),
            replaceForeign = false,
        )
    }

    fun delete(index: Int) = edit { rules -> rules.removedAt(index) }

    fun toggle(index: Int) = edit { rules -> rules.replaced(index, rules[index].copy(enabled = !rules[index].enabled)) }

    fun move(
        index: Int,
        offset: Int,
    ) = edit { rules -> rules.moved(index, index + offset) }

    fun confirmReplace() {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.save is SaveStatus.Saving) return
        persist(ready.rules, replaceForeign = true)
    }

    fun dismissConfirmation() = fail(uiText(Res.string.filters_another_active))

    private fun edit(change: (List<FilterRule>) -> List<FilterRule>) {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.save is SaveStatus.Saving) return
        persist(change(ready.rules), replaceForeign = false)
    }

    private suspend fun load() {
        _state.value =
            try {
                val set = repository.load()
                FiltersUiState.Ready(set.rules, set.folders, foreignScript = set.foreignActiveScript, editing = restored)
            } catch (e: FiltersFormatException) {
                FiltersUiState.Failed(e.text())
            } catch (e: JmapException) {
                FiltersUiState.Failed(e.userMessage())
            }
    }

    private fun persist(
        rules: List<FilterRule>,
        replaceForeign: Boolean,
    ) {
        update { it.copy(rules = rules, save = SaveStatus.Saving) }
        viewModelScope.launch {
            try {
                when (val result = repository.save(rules, replaceForeign)) {
                    is SaveResult.Saved -> update { it.copy(foreignScript = null, save = SaveStatus.Idle) }
                    is SaveResult.NeedsConfirmation ->
                        update { it.copy(foreignScript = result.scriptName, save = SaveStatus.Confirming) }
                    is SaveResult.Rejected -> fail(result.reason)
                }
            } catch (e: JmapException) {
                fail(e.userMessage())
            }
        }
    }

    private fun fail(reason: UiText) = update { it.copy(save = SaveStatus.Failed(uiText(Res.string.filters_not_saved, reason))) }

    private fun update(change: (FiltersUiState.Ready) -> FiltersUiState.Ready) {
        _state.update { if (it is FiltersUiState.Ready) change(it) else it }
    }
}

private fun FiltersFormatException.text(): UiText =
    when (this) {
        is FiltersFormatException.Unreadable -> uiText(Res.string.filters_unreadable)
        is FiltersFormatException.Newer -> uiText(Res.string.filters_newer)
    }
