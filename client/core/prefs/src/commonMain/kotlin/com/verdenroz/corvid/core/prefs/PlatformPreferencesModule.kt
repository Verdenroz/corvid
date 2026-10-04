package com.verdenroz.corvid.core.prefs

import org.koin.core.module.Module

/** Supplies the [DevicePreferences] backed by this platform's per-user file. */
expect val platformPreferencesModule: Module
