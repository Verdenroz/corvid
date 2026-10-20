package com.verdenroz.corvid

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.core.net.toUri
import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.data.InstallResult
import com.verdenroz.corvid.core.model.AppPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Installs through a [PackageInstaller] session: the system asks the user and reports back to [InstallStatusReceiver]. */
class AndroidAppInstaller(private val context: Context) : AppInstaller {
    override val platform = AppPlatform.Android

    override suspend fun install(
        file: File,
        versionCode: Int,
    ): InstallResult {
        if (!canInstall()) return InstallResult.NeedsPermission
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setSize(file.length()) }
        val id = installer.createSession(params)
        val verdict = InstallSessions.expect(id)
        return try {
            withContext(Dispatchers.IO) { commit(installer, id, file) }
            verdict.await()
        } catch (e: IOException) {
            InstallResult.Failed(e.message ?: "Could not write the update")
        } finally {
            InstallSessions.forget(id)
        }
    }

    override fun canInstall() = context.packageManager.canRequestPackageInstalls()

    override fun allowInstalls() {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun commit(
        installer: PackageInstaller,
        id: Int,
        file: File,
    ) {
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("corvid.apk", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // The system adds its status extras to this intent, so it has to be mutable.
            val status = Intent(context, InstallStatusReceiver::class.java)
            val sender = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(sender.intentSender)
        }
    }
}
