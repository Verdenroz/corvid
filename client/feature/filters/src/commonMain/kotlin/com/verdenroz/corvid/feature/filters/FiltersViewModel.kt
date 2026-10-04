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
import kotlinx.coroutines.flow.update
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
    ) = edit { rules -> if (index == null) rules + rule else rules.replaced(index, rule) }

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

    fun dismissConfirmation() = fail("another script is active")

    private fun edit(change: (List<FilterRule>) -> List<FilterRule>) {
        val ready = _state.value as? FiltersUiState.Ready ?: return
        if (ready.save is SaveStatus.Saving) return
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

    private fun fail(reason: String) = update { it.copy(save = SaveStatus.Failed("Not saved: $reason")) }

    private fun update(change: (FiltersUiState.Ready) -> FiltersUiState.Ready) {
        _state.update { if (it is FiltersUiState.Ready) change(it) else it }
    }
}
