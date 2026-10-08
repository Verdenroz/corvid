package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.keyring.DeviceRequest
import com.verdenroz.corvid.core.text.UiText

/** What the lock gate swaps every route for. */
sealed interface AppLockState {
    data object SignedOut : AppLockState

    /** [needsDeviceName] is true only before this session has ever picked one. */
    data class Connecting(val needsDeviceName: Boolean) : AppLockState

    /** Repositories live in the unlocked Koin scope, not here; [hosts] are the servers this session signed in to. */
    data class Unlocked(val hosts: AccountHosts) : AppLockState

    /** Covers a network failure and a damaged key file: neither wipes anything or reads as
     *  Revoked, so they share one pane. */
    data class Unreachable(val reason: UiText) : AppLockState

    data object Locked : AppLockState

    data object Revoked : AppLockState

    /** The server holds no key for [deviceName]: its first sign-in, or a revoke. A master device must approve one. */
    data class NeedsEnrollment(val deviceName: String, val request: EnrollmentRequest) : AppLockState
}

/** Where this device's ask for a key stands. */
sealed interface EnrollmentRequest {
    data object Opening : EnrollmentRequest

    /** Waiting for a master to approve [request]. */
    data class Open(val request: DeviceRequest) : EnrollmentRequest

    data object Expired : EnrollmentRequest

    data object TooManyOpen : EnrollmentRequest
}
