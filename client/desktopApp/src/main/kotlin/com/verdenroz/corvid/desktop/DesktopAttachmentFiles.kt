package com.verdenroz.corvid.desktop

import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.Handoff
import com.verdenroz.corvid.core.data.PickedFile
import com.verdenroz.corvid.core.data.UNKNOWN_FILE_TYPE
import com.verdenroz.corvid.core.data.safeFileName
import com.verdenroz.corvid.core.data.uniqueFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Kinds a desktop may run rather than show; these are saved and left for the user to open. */
private val RUNNABLE =
    setOf(
        "appimage", "bat", "bin", "cmd", "com", "deb", "desktop", "exe", "jar", "js",
        "lnk", "msi", "ps1", "py", "rpm", "run", "scr", "sh", "vbs",
    )

/** Picks through AWT's file dialog, and saves each opened attachment into [folder] before asking the desktop to show it. */
class DesktopAttachmentFiles(
    private val folder: File = File(System.getProperty("user.home"), "Downloads"),
    private val show: (File) -> Unit = ::showOnDesktop,
) : AttachmentFiles {
    override suspend fun pick(): List<PickedFile> {
        val dialog = FileDialog(null as Frame?).apply { isMultipleMode = true }
        dialog.isVisible = true
        return dialog.files.map { file ->
            val type = Files.probeContentType(file.toPath()) ?: UNKNOWN_FILE_TYPE
            PickedFile(file.name, type, file.length()) { withContext(Dispatchers.IO) { file.readBytes() } }
        }
    }

    override suspend fun open(
        name: String,
        type: String,
        bytes: ByteArray,
    ): Handoff =
        withContext(Dispatchers.IO) {
            try {
                folder.mkdirs()
                val target = File(folder, uniqueFileName(safeFileName(name)) { File(folder, it).exists() })
                target.writeBytes(bytes)
                if (target.extension.lowercase() !in RUNNABLE) show(target)
                Handoff.Saved(target.path.homeRelative())
            } catch (e: IOException) {
                Handoff.Failed(e.message ?: name)
            }
        }
}

private fun String.homeRelative(): String {
    val home = System.getProperty("user.home")
    return if (startsWith("$home/")) "~${removePrefix(home)}" else this
}

private fun showOnDesktop(file: File) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) return
    try {
        Desktop.getDesktop().open(file)
    } catch (_: IOException) {
        // No viewer for this type; the file stays saved where the reader says it is.
    }
}
