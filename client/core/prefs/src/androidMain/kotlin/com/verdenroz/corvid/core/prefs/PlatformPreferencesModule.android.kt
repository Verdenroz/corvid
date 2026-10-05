package com.verdenroz.corvid.core.prefs

import org.koin.android.ext.koin.androidContext
import java.io.File

actual val platformPreferencesModule = preferencesModule { File(androidContext().filesDir, "device.preferences_pb") }
