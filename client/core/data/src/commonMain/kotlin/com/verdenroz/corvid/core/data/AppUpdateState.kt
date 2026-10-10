package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.AppRelease
import com.verdenroz.corvid.core.text.UiText

sealed interface AppUpdateState {
    data object UpToDate : AppUpdateState

    data class Available(val release: AppRelease) : AppUpdateState

    /** [progress] is the fraction received, 0 to 1. */
    data class Downloading(val release: AppRelease, val progress: Float) : AppUpdateState

    /** The build is verified and the platform installer has it. */
    data class Ready(val release: AppRelease) : AppUpdateState

    /** The platform will not install builds for this app until the user allows it. */
    data class NeedsPermission(val release: AppRelease) : AppUpdateState

    /** [release] is what a retry would fetch. */
    data class Failed(val release: AppRelease, val message: UiText) : AppUpdateState
}
