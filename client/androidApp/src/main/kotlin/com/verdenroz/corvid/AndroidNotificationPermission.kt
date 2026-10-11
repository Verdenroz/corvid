package com.verdenroz.corvid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import com.verdenroz.corvid.app.NotificationPermission

/** Android 13's runtime notification permission; the activity attaches its launcher, since only it can show the prompt. */
class AndroidNotificationPermission(private val context: Context) : NotificationPermission {
    private var launcher: ActivityResultLauncher<String>? = null
    private var asked = false

    fun attach(launcher: ActivityResultLauncher<String>?) {
        this.launcher = launcher
    }

    override fun askOnce() {
        if (!asked) ask()
    }

    override fun ask() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        asked = true
        launcher?.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
