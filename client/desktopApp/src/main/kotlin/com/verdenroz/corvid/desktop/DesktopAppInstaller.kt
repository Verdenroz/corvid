package com.verdenroz.corvid.desktop

import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.data.InstallResult
import com.verdenroz.corvid.core.model.AppPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/** Swaps a verified tarball into the managed install that `scripts/install-desktop.sh` sets up, then restarts into it. */
class DesktopAppInstaller(
    private val installation: DesktopInstall = DesktopInstall(dataRoot()),
    private val appPath: String? = System.getProperty("jpackage.app-path"),
    private val relaunch: (File) -> Unit = ::relaunchAndExit,
) : AppInstaller {
    override val platform = if (System.getProperty("os.name").startsWith("Linux")) AppPlatform.Linux else null

    override suspend fun install(file: File): InstallResult {
        if (!installation.manages(appPath)) return InstallResult.Failed("Install Corvid with the setup script to get automatic updates")
        try {
            withContext(Dispatchers.IO) { installation.install(file) }
        } catch (e: IOException) {
            return InstallResult.Failed(e.message ?: "Could not install the update")
        }
        relaunch(installation.launcher)
        return InstallResult.Started
    }
}

private fun dataRoot(): File {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() } ?: "${System.getProperty("user.home")}/.local/share"
    return File(base, "corvid")
}

// The jpackage launcher treats a process carrying this as its own re-exec and skips the JVM arguments.
private const val JPACKAGE_LAUNCHER_ENV = "_JPACKAGE_LAUNCHER"

// Waits for the process to exit first so the preferences file is never open in two.
internal fun relaunchCommand(
    pid: Long,
    launcher: File,
    parentEnv: Map<String, String> = System.getenv(),
): ProcessBuilder {
    val wait = "while kill -0 \"\$1\" 2>/dev/null; do sleep 0.2; done; exec \"\$2\""
    return ProcessBuilder("sh", "-c", wait, "sh", "$pid", launcher.path).inheritIO().apply {
        environment().apply {
            clear()
            putAll(parentEnv - JPACKAGE_LAUNCHER_ENV)
        }
    }
}

private fun relaunchAndExit(launcher: File) {
    relaunchCommand(ProcessHandle.current().pid(), launcher).start()
    exitProcess(0)
}
