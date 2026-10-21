package com.verdenroz.corvid.feature.filters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.FilterSet
import com.verdenroz.corvid.core.data.FiltersFormatException
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.SaveResult
import com.verdenroz.corvid.core.data.SuggestedRules
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.launchToEnd
import com.verdenroz.corvid.core.ui.replaceDeclined
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_newer
import com.verdenroz.corvid.feature.filters.resources.filters_not_saved
import com.verdenroz.corvid.feature.filters.resources.filters_unreadable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FiltersViewModel(
    private val repository: FiltersRepository,
    private val suggested: SuggestedRules,
    private val restored: RuleDraft? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<FiltersUiState>(FiltersUiState.Loading)
    val state: StateFlow<FiltersUiState> = _state

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        _state.value = FiltersUiState.Loading
        viewModelScope.launch { load() }
    }

    /** A null [index] starts a new rule. */
    fun startEdit(index: Int?) =
        update {
            it.copy(editing = RuleDraft(index, index?.let { i -> it.rules[i] } ?: FilterRule.blank()), confirmingDelete = null)
        }

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

    /** Deleting saves to the server at once with no undo, so it asks first; [confirmDelete] does it. */
    fun delete(index: Int) = update { if (it.save is SaveStatus.Saving) it else it.copy(confirmingDelete = it.rules[index]) }

    /** Does nothing while a save runs: the question stays open with its Delete disabled. */
    fun confirmDelete() {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        val asked = ready.confirmingDelete ?: return
        if (ready.save is SaveStatus.Saving) return
        keepRule()
        persist(ready.rules - asked, replaceForeign = false)
    }

    fun keepRule() = update { it.copy(confirmingDelete = null) }

    fun toggle(index: Int) {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.save is SaveStatus.Saving) return
        val rule = ready.rules[index]
        val toggled = rule.copy(enabled = !rule.enabled)
        if (ready.confirmingDelete == rule) update { it.copy(confirmingDelete = toggled) }
        persist(ready.rules.replaced(index, toggled), replaceForeign = false)
    }

    fun move(
        index: Int,
        offset: Int,
    ) = edit { rules -> rules.moved(index, index + offset) }

    fun confirmReplace() {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.save is SaveStatus.Saving) return
        ready.confirmingSuggestion?.let { return accept(it, replaceForeign = true) }
        persist(ready.rules, replaceForeign = true)
    }

    fun dismissConfirmation() =
        update {
            it.copy(
                confirmingSuggestion = null,
                save = SaveStatus.Failed(replaceDeclined()),
            )
        }

    fun acceptSuggestion(suggestion: RuleSuggestion) = accept(suggestion, replaceForeign = false)

    fun dismissSuggestion(suggestion: RuleSuggestion) {
        update { it.copy(suggestions = it.suggestions - suggestion) }
        launchToEnd {
            try {
                suggested.dismiss(suggestion)
            } catch (e: JmapException) {
                fail(e.userMessage())
            }
        }
    }

    private fun accept(
        suggestion: RuleSuggestion,
        replaceForeign: Boolean,
    ) {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.save is SaveStatus.Saving) return
        update { it.copy(save = SaveStatus.Saving, confirmingSuggestion = null) }
        launchToEnd {
            try {
                when (val result = suggested.accept(suggestion, replaceForeign)) {
                    is SaveResult.Saved -> reload()
                    is SaveResult.NeedsConfirmation ->
                        update {
                            it.copy(
                                foreignScript = result.scriptName,
                                save = SaveStatus.Confirming,
                                confirmingSuggestion = suggestion,
                            )
                        }
                    is SaveResult.Rejected -> fail(result.reason)
                }
            } catch (e: JmapException) {
                fail(e.userMessage())
            }
        }
    }

    private suspend fun reload() {
        val (set, pending) = loadWithSuggestions()
        update { it.copy(rules = set.rules, folders = set.folders, suggestions = pending, foreignScript = null, save = SaveStatus.Idle) }
    }

    private suspend fun loadWithSuggestions(): Pair<FilterSet, List<RuleSuggestion>> =
        coroutineScope {
            val fetched = async { noneOnFailure { suggested.fetch() } }
            val set = repository.load()
            set to noneOnFailure { suggested.pending(fetched.await(), set) }
        }

    private suspend fun noneOnFailure(block: suspend () -> List<RuleSuggestion>): List<RuleSuggestion> =
        try {
            block()
        } catch (e: JmapException) {
            emptyList()
        }

    private fun edit(change: (List<FilterRule>) -> List<FilterRule>) {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.save is SaveStatus.Saving) return
        persist(change(ready.rules), replaceForeign = false)
    }

    private suspend fun load() {
        _state.value =
            try {
                val (set, pending) = loadWithSuggestions()
                FiltersUiState.Ready(
                    set.rules,
                    set.folders,
                    foreignScript = set.foreignActiveScript,
                    editing = restored,
                    suggestions = pending,
                )
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
        launchToEnd {
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
