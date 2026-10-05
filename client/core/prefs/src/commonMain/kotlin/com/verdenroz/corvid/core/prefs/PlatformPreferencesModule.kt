package com.verdenroz.corvid.core.prefs

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.corvid.core.common.di.applicationScopeQualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.io.File

/** Supplies the [DevicePreferences] backed by this platform's per-user file. */
expect val platformPreferencesModule: Module

internal fun preferencesModule(file: Scope.() -> File): Module =
    module {
        single {
            val scope = get<CoroutineScope>(applicationScopeQualifier)
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = { file() },
                )
            DevicePreferences(store, scope, runBlocking { decode(store.data.first()) })
        }
    }
