package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository

/** What the app-common gate (decision 2) swaps every route for. */
sealed interface AppLockState {
    data object SignedOut : AppLockState

    /** [needsDeviceName] is true only before this session has ever picked one. */
    data class Connecting(val needsDeviceName: Boolean) : AppLockState

    /** [mailboxRepository] wraps the same JmapClient the unlocked Koin scope holds
     *  (decision 10): a screen built from it never needs to know how it was built. */
    data class Unlocked(val mailboxRepository: MailboxRepository, val composeRepository: ComposeRepository) : AppLockState

    /** Covers a network failure, a damaged key file, and an ambiguous folder set: none
     *  of the three wipes anything or reads as Revoked, so they share one pane. */
    data class Unreachable(val reason: String) : AppLockState

    data object Locked : AppLockState

    data object Revoked : AppLockState

    /** No key exists for [deviceName] yet: could be its first sign-in, or a revoke.
     *  Enrolling needs an explicit tap, never an automatic call (decision 10). */
    data class NeedsEnrollment(val deviceName: String) : AppLockState
}
