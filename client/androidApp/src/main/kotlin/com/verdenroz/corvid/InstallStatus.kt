package com.verdenroz.corvid

import android.content.pm.PackageInstaller
import com.verdenroz.corvid.core.data.InstallResult

/**
 * An abort is the user's answer only when the system [prompted] them first. One that arrives without a prompt was
 * the device refusing the install, as Samsung's Auto Blocker does, and reads as a failure saying [unprompted].
 */
internal fun installResult(
    status: Int,
    message: String?,
    prompted: Boolean,
    unprompted: String,
): InstallResult =
    when {
        status == PackageInstaller.STATUS_SUCCESS -> InstallResult.Started
        status == PackageInstaller.STATUS_FAILURE_ABORTED && prompted -> InstallResult.Cancelled
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Failed(listOfNotNull(unprompted, message).joinToString(" "))
        else -> InstallResult.Failed(message ?: "Install failed, status $status")
    }
