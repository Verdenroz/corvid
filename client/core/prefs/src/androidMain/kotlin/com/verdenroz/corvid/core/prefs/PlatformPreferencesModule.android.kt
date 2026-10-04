package com.verdenroz.corvid.core.prefs

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.corvid.core.common.di.applicationScopeQualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

actual val platformPreferencesModule =
    module {
        single {
            val scope = get<CoroutineScope>(applicationScopeQualifier)
            val file = File(androidContext().filesDir, "device.preferences_pb")
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = { file },
                )
            DevicePreferences(store, scope, runBlocking { decode(store.data.first()) })
        }
    }
