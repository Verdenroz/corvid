package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass

internal fun <T : ViewModel> ViewModelStore.model(
    key: String,
    type: KClass<T>,
    create: () -> T,
): T =
    ViewModelProvider.create(
        this,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(
                modelClass: KClass<V>,
                extras: CreationExtras,
            ): V = create() as V
        },
    )[key, type]

/** View models scoped to a back-stack entry, dropped with the entry rather than kept like a tab's. */
internal class EntryModels {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun <T : ViewModel> of(
        entryKey: String,
        type: KClass<T>,
        create: () -> T,
    ): T = stores.getOrPut(entryKey) { ViewModelStore() }.model(type.simpleName.orEmpty(), type, create)

    fun retainOnly(entryKeys: Set<String>) {
        (stores.keys - entryKeys).forEach { stores.remove(it)?.clear() }
    }

    fun clear() = retainOnly(emptySet())
}
