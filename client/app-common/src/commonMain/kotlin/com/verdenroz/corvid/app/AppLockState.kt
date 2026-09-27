package com.verdenroz.corvid.app

/** What the app-common gate (decision 2) swaps every route for. */
sealed interface AppLockState {
    data object SignedOut : AppLockState

    /** [needsDeviceName] is true only before this session has ever picked one. */
    data class Connecting(val needsDeviceName: Boolean) : AppLockState

    data object Unlocked : AppLockState

    /** Covers a network failure, a damaged key file, and an ambiguous folder set: none
     *  of the three wipes anything or reads as Revoked, so they share one pane. */
    data class Unreachable(val reason: String) : AppLockState

    data object Locked : AppLockState

    data object Revoked : AppLockState
}
