package com.verdenroz.corvid.feature.filters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.FiltersFormatException
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.SaveResult
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.userMessage
import com.verdenroz.corvid.core.model.FilterRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FiltersViewModel(private val repository: FiltersRepository) : ViewModel() {
    private val _state = MutableStateFlow<FiltersUiState>(FiltersUiState.Loading)
    val state: StateFlow<FiltersUiState> = _state

    init {
        viewModelScope.launch { load() }
    }

    /** A null [index] appends. */
    fun saveRule(
        index: Int?,
        rule: FilterRule,
    ) = edit { rules -> if (index == null) rules + rule else rules.mapIndexed { i, old -> if (i == index) rule else old } }

    fun delete(index: Int) = edit { rules -> rules.filterIndexed { i, _ -> i != index } }

    fun toggle(index: Int) = edit { rules -> rules.mapIndexed { i, rule -> if (i == index) rule.copy(enabled = !rule.enabled) else rule } }

    fun move(
        index: Int,
        offset: Int,
    ) = edit { rules ->
        val target = index + offset
        if (target in rules.indices) rules.toMutableList().apply { add(target, removeAt(index)) } else rules
    }

    fun confirmReplace() {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.saving) return
        persist(ready.rules, replaceForeign = true)
    }

    fun dismissConfirmation() {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        _state.value = ready.copy(confirming = false, error = "not saved: another script is active")
    }

    private fun edit(change: (List<FilterRule>) -> List<FilterRule>) {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.saving) return
        persist(change(ready.rules), replaceForeign = false)
    }

    private suspend fun load() {
        _state.value =
            try {
                val set = repository.load()
                FiltersUiState.Ready(set.rules, set.folders, foreignScript = set.foreignActiveScript)
            } catch (e: FiltersFormatException) {
                FiltersUiState.Failed(e.message.orEmpty())
            } catch (e: JmapException) {
                FiltersUiState.Failed(e.userMessage())
            }
    }

    private fun persist(
        rules: List<FilterRule>,
        replaceForeign: Boolean,
    ) {
        val editing = (_state.value as FiltersUiState.Ready).copy(rules = rules, saving = true, confirming = false, error = null)
        _state.value = editing
        viewModelScope.launch {
            _state.value =
                try {
                    when (val result = repository.save(rules, replaceForeign)) {
                        is SaveResult.Saved -> editing.copy(saving = false, foreignScript = null)
                        is SaveResult.NeedsConfirmation ->
                            editing.copy(saving = false, foreignScript = result.scriptName, confirming = true)
                        is SaveResult.Rejected -> editing.copy(saving = false, error = "the server rejected the filters (${result.reason})")
                    }
                } catch (e: JmapException) {
                    editing.copy(saving = false, error = e.userMessage())
                }
        }
    }
}
