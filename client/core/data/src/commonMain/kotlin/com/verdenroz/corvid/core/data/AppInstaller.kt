package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.AppPlatform
import java.io.File

sealed interface InstallResult {
    /** The platform installer has the file and took over. */
    data object Started : InstallResult

    data object NotSupported : InstallResult
}

/** Hands a verified build to the platform's installer; each platform app provides one. */
interface AppInstaller {
    /** The build this device takes, or null where the platform cannot be updated from here. */
    val platform: AppPlatform?

    suspend fun install(file: File): InstallResult
}
