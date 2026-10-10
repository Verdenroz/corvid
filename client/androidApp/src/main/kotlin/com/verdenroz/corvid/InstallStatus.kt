package com.verdenroz.corvid

import android.content.pm.PackageInstaller
import com.verdenroz.corvid.core.data.InstallResult

internal fun installResult(
    status: Int,
    message: String?,
): InstallResult =
    when (status) {
        PackageInstaller.STATUS_SUCCESS -> InstallResult.Started
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
        else -> InstallResult.Failed(message ?: "Install failed, status $status")
    }
