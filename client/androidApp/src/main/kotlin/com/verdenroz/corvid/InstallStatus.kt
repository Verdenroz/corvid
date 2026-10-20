package com.verdenroz.corvid

import android.content.pm.PackageInstaller
import com.verdenroz.corvid.core.data.InstallResult

/**
 * An abort that arrives without a prompt was the device refusing the install and reads as a failure saying [unprompted].
 * After a prompt it is the user's answer, unless the device has a [blocker] that can also abort there, as Samsung's
 * Auto Blocker does; then it reads as a failure saying [blocker], since the two cannot be told apart.
 */
internal fun installResult(
    status: Int,
    message: String?,
    prompted: Boolean,
    unprompted: String,
    blocker: String?,
): InstallResult =
    when {
        status == PackageInstaller.STATUS_SUCCESS -> InstallResult.Started
        status == PackageInstaller.STATUS_FAILURE_ABORTED && prompted -> blocker?.let(InstallResult::Failed) ?: InstallResult.Cancelled
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Failed(listOfNotNull(unprompted, message).joinToString(" "))
        else -> InstallResult.Failed(message ?: "Install failed, status $status")
    }
