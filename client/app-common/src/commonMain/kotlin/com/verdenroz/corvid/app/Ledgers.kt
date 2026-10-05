package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModelStore
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel

/** One ledger per tab while the mailbox is unlocked, so a tab keeps its loaded pages behind a reader or another tab. */
internal class Ledgers(private val repository: MailboxRepository) {
    private val store = ViewModelStore()

    fun of(tab: LedgerTab): LedgerViewModel = store.model(tab.name, LedgerViewModel::class) { LedgerViewModel(repository, tab) }

    fun clear() = store.clear()
}
