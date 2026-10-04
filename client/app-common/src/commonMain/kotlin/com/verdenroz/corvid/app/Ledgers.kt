package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel
import kotlin.reflect.KClass

/** One ledger per tab while the mailbox is unlocked, so a tab keeps its loaded pages behind a reader or another tab. */
internal class Ledgers(private val repository: MailboxRepository) {
    private val store = ViewModelStore()

    fun of(tab: LedgerTab): LedgerViewModel =
        ViewModelProvider.create(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: KClass<T>,
                    extras: CreationExtras,
                ): T = LedgerViewModel(repository, tab) as T
            },
        )[tab.name, LedgerViewModel::class]

    fun clear() = store.clear()
}
