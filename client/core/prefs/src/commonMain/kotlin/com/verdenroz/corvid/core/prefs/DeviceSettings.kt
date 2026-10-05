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

/** Choices that belong to this device and hold no mail, so they live in a plain file. [noticesSeen] is the newest server notice id shown here. */
data class DeviceSettings(
    val theme: ThemeChoice = ThemeChoice.System,
    val autoLock: AutoLock = AutoLock.FiveMinutes,
    val noticesSeen: Long = 0,
)
