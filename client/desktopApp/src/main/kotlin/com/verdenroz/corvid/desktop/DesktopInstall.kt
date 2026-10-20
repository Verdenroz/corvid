package com.verdenroz.corvid.desktop

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Unpacks release tarballs into `versions/<versionCode>` under [root], as `scripts/install-desktop.sh` does, and points
 * the `current` symlink at the one just installed. Every failure leaves `current` where it was.
 */
class DesktopInstall(private val root: File) {
    private val versions = File(root, "versions")

    val launcher: File get() = File(root, "current/corvid/bin/corvid")

    fun manages(appPath: String?): Boolean {
        if (appPath == null || !versions.isDirectory) return false
        return File(appPath).toPath().toRealPath().startsWith(versions.toPath().toRealPath())
    }

    @Throws(IOException::class)
    fun install(
        tarball: File,
        versionCode: Int,
    ) {
        versions.mkdirs()
        val partial = File(versions, ".partial-$versionCode")
        try {
            partial.deleteRecursively()
            partial.mkdirs()
            extract(tarball, partial)
            if (!File(partial, "corvid/bin/corvid").canExecute()) throw IOException("The update has no runnable launcher")
            val target = File(versions, versionCode.toString())
            target.deleteRecursively()
            Files.move(partial.toPath(), target.toPath(), ATOMIC_MOVE)
            point(target)
        } finally {
            partial.deleteRecursively()
        }
        installed().sortedDescending().drop(KEPT).forEach { File(versions, it.toString()).deleteRecursively() }
    }

    private fun installed() = versions.list().orEmpty().mapNotNull { it.toIntOrNull() }

    private fun extract(
        tarball: File,
        into: File,
    ) {
        val tar = ProcessBuilder("tar", "-xzf", tarball.path, "-C", into.path).redirectErrorStream(true).start()
        val output = tar.inputStream.bufferedReader().readText().trim()
        if (tar.waitFor() != 0) throw IOException(output.ifEmpty { "tar could not unpack the update" })
    }

    private fun point(target: File) {
        val link = File(root, ".current-tmp").toPath()
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, File("versions", target.name).toPath())
        Files.move(link, File(root, "current").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private companion object {
        const val KEPT = 2
    }
}
