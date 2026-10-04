package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.Mailbox

data class FilterSet(val rules: List<FilterRule>, val foreignActiveScript: String?)

sealed interface SaveResult {
    data object Saved : SaveResult

    /** Nothing was written: activating Corvid's script would deactivate [scriptName]. */
    data class NeedsConfirmation(val scriptName: String) : SaveResult

    /** The server refused the script, or the rules cannot be written; [reason] is its own words. */
    data class Rejected(val reason: String) : SaveResult
}

class FiltersRepository(
    jmapClient: JmapClient,
    private val mailboxRepository: MailboxRepository,
) {
    private val store = SieveScriptStore(jmapClient, mailboxRepository)

    suspend fun load(): FilterSet = store.load().let { FilterSet(it.script.rules, it.foreignActiveScript) }

    /** Each folder's name is its full path, `Finance/Receipts`, so same-named children stay distinct. */
    suspend fun folders(): List<FilterAction.MoveToFolder> {
        val all = mailboxRepository.mailboxes()
        val byId = all.associateBy { it.id }
        return all.map { FilterAction.MoveToFolder(it.id, it.path(byId)) }
    }

    suspend fun save(
        rules: List<FilterRule>,
        replaceForeign: Boolean,
    ): SaveResult = store.save(store.load().script.copy(rules = rules), replaceForeign)
}

private fun Mailbox.path(byId: Map<String, Mailbox>): String = parentId?.let { byId[it] }?.let { "${it.path(byId)}/$name" } ?: name
