package com.verdenroz.corvid.core.auth

import org.publicvalue.multiplatform.oidc.preferences.Preferences
import org.publicvalue.multiplatform.oidc.preferences.PreferencesSingletonFactory

/**
 * kotlin-multiplatform-oidc's JVM `PreferencesFactory` is `final` and writes its
 * PKCE flow state to a file under `$HOME`. Its singleton instance is shared
 * process-wide across every `PreferencesSingletonFactory` subclass, so calling
 * [seed] once, before the first login, wins the race: `JvmCodeAuthFlowFactory`'s
 * own `PreferencesFactory().getOrCreate(...)` finds this instance already
 * cached and never touches disk.
 */
object InMemoryPreferencesFactory : PreferencesSingletonFactory() {
    suspend fun seed() {
        getOrCreate("corvid-oidc")
    }

    override fun create(filename: String): Preferences = InMemoryPreferences()
}

private class InMemoryPreferences : Preferences {
    private val values = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }
}
