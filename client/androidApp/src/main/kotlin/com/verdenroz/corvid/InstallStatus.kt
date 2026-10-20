package com.verdenroz.corvid

import android.content.pm.PackageInstaller
import com.verdenroz.corvid.core.data.InstallResult

/**
 * Every abort is a failure. One that arrives without a prompt was the device refusing the install and says [unprompted].
 * One after a prompt says [aborted]: the user declining and a setting such as Samsung's Auto Blocker refusing the install
 * there report the same status, so the message has to cover both.
 */
internal fun installResult(
    status: Int,
    message: String?,
    prompted: Boolean,
    unprompted: String,
    aborted: String,
): InstallResult =
    when {
        status == PackageInstaller.STATUS_SUCCESS -> InstallResult.Started
        status == PackageInstaller.STATUS_FAILURE_ABORTED && prompted -> InstallResult.Failed(aborted)
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Failed(listOfNotNull(unprompted, message).joinToString(" "))
        else -> InstallResult.Failed(message ?: "Install failed, status $status")
    }
