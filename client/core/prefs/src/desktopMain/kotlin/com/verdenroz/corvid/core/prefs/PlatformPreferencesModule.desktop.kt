package com.verdenroz.corvid.core.prefs

import java.io.File

actual val platformPreferencesModule =
    preferencesModule { userConfigDir().resolve("corvid").resolve("device.preferences_pb").also { it.parentFile.mkdirs() } }

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
