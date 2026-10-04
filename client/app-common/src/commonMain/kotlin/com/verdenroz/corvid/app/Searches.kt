package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.feature.mailbox.LedgerSource
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel
import kotlin.reflect.KClass

/** One ledger per search entry on the back stack, dropped with the entry rather than kept like a tab's. */
internal class Searches(private val repository: MailboxRepository) {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun of(
        entryKey: String,
        query: String,
    ): LedgerViewModel =
        ViewModelProvider.create(
            stores.getOrPut(entryKey) { ViewModelStore() },
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: KClass<T>,
                    extras: CreationExtras,
                ): T = LedgerViewModel(repository, LedgerSource.Search(query)) as T
            },
        )[LedgerViewModel::class]

    fun retainOnly(entryKeys: Set<String>) {
        (stores.keys - entryKeys).forEach { stores.remove(it)?.clear() }
    }

    fun clear() = retainOnly(emptySet())
}
