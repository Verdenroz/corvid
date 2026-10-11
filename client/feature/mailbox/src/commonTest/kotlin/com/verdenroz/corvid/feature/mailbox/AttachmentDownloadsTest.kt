package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.Handoff
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.PickedFile
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private val REPORT = Attachment("b-report", "report.pdf", "application/pdf", 8)

private class RecordingFiles(private val handoff: Handoff = Handoff.Saved("~/Downloads/report.pdf")) : AttachmentFiles {
    val opened = mutableListOf<Triple<String, String, ByteArray>>()

    override suspend fun pick(): List<PickedFile> = emptyList()

    override suspend fun open(
        name: String,
        type: String,
        bytes: ByteArray,
    ): Handoff {
        opened += Triple(name, type, bytes)
        return handoff
    }
}

class AttachmentDownloadsTest {
    @Test
    fun `opening an attachment hands the server's bytes to the platform and says where they went`() =
        runTest {
            val fake = FakeJmapServer()
            fake.blobs["b-report"] = "%PDF-1.7".encodeToByteArray()
            val files = RecordingFiles()
            val downloads = AttachmentDownloads(MailboxRepository(fake.client()), files, backgroundScope)

            downloads.open(REPORT)

            assertEquals(
                AttachmentState.Done(Handoff.Saved("~/Downloads/report.pdf")),
                downloads.states.first { it["b-report"] is AttachmentState.Done }["b-report"],
            )
            val (name, type, bytes) = files.opened.single()
            assertEquals("report.pdf" to "application/pdf", name to type)
            assertContentEquals("%PDF-1.7".encodeToByteArray(), bytes)
        }

    @Test
    fun `a file nothing on the device can show says so`() =
        runTest {
            val fake = FakeJmapServer()
            fake.blobs["b-report"] = "%PDF-1.7".encodeToByteArray()
            val downloads = AttachmentDownloads(MailboxRepository(fake.client()), RecordingFiles(Handoff.NoViewer), backgroundScope)

            downloads.open(REPORT)

            assertEquals(
                AttachmentState.Done(Handoff.NoViewer),
                downloads.states.first { it["b-report"] is AttachmentState.Done }["b-report"],
            )
        }

    @Test
    fun `an unreachable server fails the download and never reaches the platform`() =
        runTest {
            val fake = FakeJmapServer()
            val files = RecordingFiles()
            val downloads = AttachmentDownloads(MailboxRepository(fake.client()), files, backgroundScope)
            fake.unreachable = true

            downloads.open(REPORT)

            assertEquals(
                AttachmentState.Unfetched(JmapException(JmapError.Unreachable(IOException())).userMessage()),
                downloads.states.first { it["b-report"] is AttachmentState.Unfetched }["b-report"],
            )
            assertEquals(emptyList(), files.opened)
        }
}
