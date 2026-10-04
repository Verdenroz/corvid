package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef

data class FilterSet(
    val rules: List<FilterRule>,
    val foreignActiveScript: String?,
    val folders: List<MailboxRef>,
)

sealed interface SaveResult {
    data object Saved : SaveResult

    /** Nothing was written: activating Corvid's script would deactivate [scriptName]. */
    data class NeedsConfirmation(val scriptName: String) : SaveResult

    /** The server refused the script, or the rules cannot be written; [reason] is its own words. */
    data class Rejected(val reason: String) : SaveResult
}

class FiltersRepository(jmapClient: JmapClient) {
    private val store = SieveScriptStore(jmapClient)

    suspend fun load(): FilterSet = store.load().let { FilterSet(it.script.rules, it.foreignActiveScript, it.folders) }

    suspend fun save(
        rules: List<FilterRule>,
        replaceForeign: Boolean,
    ): SaveResult = store.save(store.load().script.copy(rules = rules), replaceForeign)
}
