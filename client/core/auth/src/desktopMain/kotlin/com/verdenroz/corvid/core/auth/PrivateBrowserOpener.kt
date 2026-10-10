package com.verdenroz.corvid.core.auth

import io.ktor.http.Url
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Opens [url] in a private window of the default browser on Linux. Elsewhere, or when the browser
 * declares no private-window action, it falls back to a normal window, which shares the daily
 * browser session.
 */
fun openInPrivateWindow(url: Url) {
    val entry = if (System.getProperty("os.name").startsWith("Linux")) defaultBrowserEntry() else null
    val argv = entry?.let { privateWindowCommand(it, url.toString()) }
    if (argv == null) {
        Desktop.getDesktop().browse(URI(url.toString()))
        return
    }
    ProcessBuilder(argv)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

private fun defaultBrowserEntry(): String? {
    val name =
        ProcessBuilder("xdg-settings", "get", "default-web-browser")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
    if (name.isEmpty()) return null
    val home = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() } ?: "${System.getProperty("user.home")}/.local/share"
    val dirs = System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotEmpty() } ?: "/usr/local/share:/usr/share"
    return (listOf(home) + dirs.split(':'))
        .map { File(it, "applications/$name") }
        .firstOrNull { it.isFile }
        ?.readText()
}
