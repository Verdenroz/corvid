package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.AppPlatform
import java.io.File

sealed interface InstallResult {
    /** The platform installed the build or took over. */
    data object Started : InstallResult

    data object NotSupported : InstallResult

    /** The user declined the platform's confirmation. */
    data object Cancelled : InstallResult

    /** The user has not yet let this app install builds; see [AppInstaller.allowInstalls]. */
    data object NeedsPermission : InstallResult

    data class Failed(val message: String) : InstallResult
}

/** Hands a verified build to the platform's installer; each platform app provides one. */
interface AppInstaller {
    /** The build this device takes, or null where the platform cannot be updated from here. */
    val platform: AppPlatform?

    suspend fun install(file: File): InstallResult

    /** Opens the screen where the user lets this app install builds, after [InstallResult.NeedsPermission]. */
    fun allowInstalls() {}
}
