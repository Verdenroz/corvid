package com.verdenroz.corvid

import android.content.pm.PackageInstaller
import com.verdenroz.corvid.core.data.InstallResult
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallStatusTest {
    @Test
    fun `a successful install starts the new build`() {
        assertEquals(InstallResult.Started, installResult(PackageInstaller.STATUS_SUCCESS, null))
    }

    @Test
    fun `an install the user aborted is a cancellation`() {
        assertEquals(InstallResult.Cancelled, installResult(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED"))
    }

    @Test
    fun `every other failure carries the system's message`() {
        val failures =
            listOf(
                PackageInstaller.STATUS_FAILURE,
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_STORAGE,
            )
        for (status in failures) {
            assertEquals(InstallResult.Failed("no space"), installResult(status, "no space"))
        }
    }

    @Test
    fun `a failure without a message still names its status`() {
        assertEquals(InstallResult.Failed("Install failed, status 6"), installResult(PackageInstaller.STATUS_FAILURE_STORAGE, null))
    }
}
