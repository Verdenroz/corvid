package com.verdenroz.corvid.core.prefs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class ThemeChoice { System, Light, Dark }

enum class AutoLock(val minutes: Int) {
    OneMinute(1),
    FiveMinutes(5),
    FifteenMinutes(15),
    ThirtyMinutes(30),
    ;

    val timeout: Duration get() = minutes.minutes
}

/**
 * The last account signed in here, so the next sign-in skips discovery. Addresses only: the tokens
 * stay in memory. [email] is absent when the account was entered by server and issuer.
 */
data class RememberedAccount(
    val server: String,
    val issuer: String,
    val email: String? = null,
)

/** Choices that belong to this device and hold no mail, so they live in a plain file. [noticesSeen] is the newest server notice id shown here. */
data class DeviceSettings(
    val theme: ThemeChoice = ThemeChoice.System,
    val autoLock: AutoLock = AutoLock.FiveMinutes,
    val noticesSeen: Long = 0,
    val account: RememberedAccount? = null,
)
