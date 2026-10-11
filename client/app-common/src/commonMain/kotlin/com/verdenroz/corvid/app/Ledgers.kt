package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModelStore
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel
import com.verdenroz.corvid.feature.mailbox.MailActions

/** One ledger per tab while the mailbox is unlocked, so a tab keeps its loaded pages behind a reader or another tab. */
internal class Ledgers(private val repository: MailboxRepository) {
    private val store = ViewModelStore()
    private val opened = mutableMapOf<LedgerTab, LedgerViewModel>()

    val actions: MailActions = store.model("actions", MailActions::class) { MailActions(repository) }

    fun of(tab: LedgerTab): LedgerViewModel =
        opened.getOrPut(tab) { store.model(tab.name, LedgerViewModel::class) { LedgerViewModel(repository, tab) } }

    fun refreshAll() = opened.values.forEach(LedgerViewModel::refresh)

    fun clearSelections() = opened.values.forEach(LedgerViewModel::clearSelection)

    fun clear() {
        store.clear()
        opened.clear()
    }
}
