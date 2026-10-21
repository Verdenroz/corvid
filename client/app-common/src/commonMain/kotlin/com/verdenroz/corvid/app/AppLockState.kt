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

    /** The server or the key could not be reached; nothing is wiped, and it does not read as Revoked. */
    data class Unreachable(val reason: UiText) : AppLockState

    /** The server answered with a key this app cannot read; nothing is wiped, and it does not read as Revoked. */
    data object KeyDamaged : AppLockState

    /** The server answered but serves no device routes, so no key can be asked for. */
    data object DeviceApprovalOff : AppLockState

    data object Locked : AppLockState

    data object Revoked : AppLockState

    /** The server holds no key for [deviceName]: its first sign-in, or a revoke. A master device must approve one. */
    data class NeedsEnrollment(val deviceName: String, val request: EnrollmentRequest) : AppLockState
}

/** Where this device's ask for a key stands. */
sealed interface EnrollmentRequest {
    data object Opening : EnrollmentRequest

    /** Waiting for a master to approve [request], which may be this device itself ([selfApproval]). */
    data class Open(
        val request: DeviceRequest,
        val selfApproval: SelfApproval = SelfApproval.Idle,
    ) : EnrollmentRequest

    data object Expired : EnrollmentRequest

    data object TooManyOpen : EnrollmentRequest
}

/** This device approving its own open request with the admin passkey. */
sealed interface SelfApproval {
    data object Idle : SelfApproval

    data object Approving : SelfApproval

    data class Failed(val message: UiText) : SelfApproval
}
