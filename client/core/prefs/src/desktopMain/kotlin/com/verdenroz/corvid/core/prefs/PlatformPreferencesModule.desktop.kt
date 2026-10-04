package com.verdenroz.corvid.core.prefs

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.corvid.core.common.di.applicationScopeQualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import java.io.File

actual val platformPreferencesModule =
    module {
        single {
            val scope = get<CoroutineScope>(applicationScopeQualifier)
            val file = userConfigDir().resolve("corvid").resolve("device.preferences_pb").also { it.parentFile.mkdirs() }
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = { file },
                )
            DevicePreferences(store, scope, runBlocking { decode(store.data.first()) })
        }
    }

internal fun userConfigDir(
    osName: String = System.getProperty("os.name"),
    env: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home"),
): File =
    when {
        osName.startsWith("Windows") -> File(env("APPDATA") ?: "$home\\AppData\\Roaming")
        osName.startsWith("Mac") -> File(home, "Library/Application Support")
        else -> env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".config")
    }
