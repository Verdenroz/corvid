package com.verdenroz.corvid.desktop

import com.verdenroz.corvid.core.data.Handoff
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAttachmentFilesTest {
    private val folder = createTempDirectory("corvid-downloads").toFile().apply { deleteOnExit() }
    private val shown = mutableListOf<File>()
    private val files = DesktopAttachmentFiles(folder) { shown += it }

    @Test
    fun `an opened attachment is saved beside earlier ones under a bare name and shown`() =
        runBlocking {
            File(folder, "passwd").writeText("earlier")

            val handoff = files.open("../../etc/passwd", "text/plain", "new".encodeToByteArray())

            val saved = File(folder, "passwd (1)")
            assertEquals(Handoff.Saved(saved.path), handoff)
            assertEquals("new", saved.readText())
            assertEquals("earlier", File(folder, "passwd").readText())
            assertEquals(listOf(saved), shown)
        }

    @Test
    fun `a file the desktop could run is saved and left unopened`() =
        runBlocking {
            files.open("invoice.desktop", "application/x-desktop", "[Desktop Entry]".encodeToByteArray())

            assertEquals(true, File(folder, "invoice.desktop").exists())
            assertEquals(emptyList(), shown)
        }
}
