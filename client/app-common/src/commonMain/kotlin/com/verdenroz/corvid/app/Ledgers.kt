package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModelStore
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.feature.mailbox.LedgerSource
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel
import com.verdenroz.corvid.feature.mailbox.MailActions

/**
 * Every live ledger while the mailbox is unlocked. A tab's lives until sign-out, so it keeps its loaded pages behind
 * a reader or another tab; a search's lives with its back-stack entry.
 */
internal class Ledgers(private val repository: MailboxRepository) {
    private val store = ViewModelStore()
    private val tabs = mutableMapOf<LedgerTab, LedgerViewModel>()
    private val live = mutableSetOf<LedgerViewModel>()

    val actions: MailActions = store.model("actions", MailActions::class) { MailActions(repository) }

    fun of(tab: LedgerTab): LedgerViewModel =
        tabs.getOrPut(tab) { store.model(tab.name, LedgerViewModel::class) { tracked(LedgerViewModel(repository, tab)) } }

    fun search(
        models: EntryModels,
        entryKey: String,
        query: String,
    ): LedgerViewModel = models.of(entryKey, LedgerViewModel::class) { tracked(LedgerViewModel(repository, LedgerSource.Search(query))) }

    /** The ledgers on screen refetch now; the rest wait until they are shown. */
    fun mailChanged() = live.forEach(LedgerViewModel::mailChanged)

    /** Ticks and the last action's notice belong to the screen they were made on. */
    fun leave() {
        live.forEach(LedgerViewModel::clearSelection)
        actions.dismiss()
    }

    fun clear() {
        store.clear()
        tabs.clear()
    }

    private fun tracked(ledger: LedgerViewModel): LedgerViewModel {
        live += ledger
        ledger.addCloseable(AutoCloseable { live -= ledger })
        return ledger
    }
}
