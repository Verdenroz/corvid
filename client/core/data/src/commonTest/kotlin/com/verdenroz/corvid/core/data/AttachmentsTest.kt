package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.testing.FakeAttachment
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.size_bytes
import com.verdenroz.corvid.core.text.resources.size_gigabytes
import com.verdenroz.corvid.core.text.resources.size_kilobytes
import com.verdenroz.corvid.core.text.resources.size_megabytes
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

private val REPORT = FakeAttachment(blobId = "b-report", name = "Q3 report/final.pdf", type = "application/pdf", size = 2048)
private val LOGO =
    FakeAttachment(blobId = "b-logo", name = "logo.png", type = "image/png", size = 512, disposition = "inline", cid = "<logo@x>")
private val STRAY =
    FakeAttachment(blobId = "b-stray", name = "stray.png", type = "image/png", size = 64, disposition = "inline", cid = "<stray@x>")

private fun server(): FakeJmapServer {
    val fake = FakeJmapServer().withSendableAccount()
    fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
    fake.emails +=
        FakeEmail(
            id = "e1",
            threadId = "t1",
            mailboxIds = setOf("inbox"),
            subject = "Numbers",
            from = "alice@example.test",
            receivedAt = "2024-03-01T12:00:00Z",
            body = "attached",
            htmlBody = "<p>attached</p><img src=\"cid:logo@x\">",
            attachments = listOf(REPORT, LOGO, STRAY),
        )
    fake.blobs["b-report"] = "%PDF-1.7".encodeToByteArray()
    return fake
}

private fun picked(
    name: String,
    bytes: ByteArray,
    type: String = "text/plain",
) = PickedFile(name, type, bytes.size.toLong()) { bytes }

class AttachmentsTest {
    @Test
    fun `a message lists its files but not the inline images its html draws`() =
        runTest {
            val detail = assertIs<EmailLookup.Found>(MailboxRepository(server().client()).getEmail("e1")).detail

            assertEquals(listOf("b-report", "b-stray"), detail.attachments.map { it.blobId })
            assertEquals(Attachment("b-report", "Q3 report/final.pdf", "application/pdf", 2048), detail.attachments.first())
        }

    @Test
    fun `an image the html draws by cid loads from the server's copy of that part`() =
        runTest {
            val fake = server()
            fake.blobs["b-logo"] = byteArrayOf(1, 2, 3)
            val repository = MailboxRepository(fake.client())
            val detail = assertIs<EmailLookup.Found>(repository.getEmail("e1")).detail

            assertContentEquals(byteArrayOf(1, 2, 3), repository.messageImage(detail, "cid:LOGO@x"))
        }

    @Test
    fun `a cid the message does not carry loads nothing`() =
        runTest {
            val repository = MailboxRepository(server().client())
            val detail = assertIs<EmailLookup.Found>(repository.getEmail("e1")).detail

            assertEquals(null, repository.messageImage(detail, "cid:missing@x"))
            assertEquals(null, repository.messageImage(detail, "https://tracker.test/pixel.png"))
        }

    @Test
    fun `a list row knows its message carries a file`() =
        runTest {
            val page = assertIs<EmailPage.Found>(MailboxRepository(server().client()).page(MailFilter.InMailbox("inbox"), anchor = null))

            assertEquals(true, page.conversations.single().latest.hasAttachment)
        }

    @Test
    fun `a downloaded attachment is the blob the server holds, whatever its name`() =
        runTest {
            val repository = MailboxRepository(server().client())
            val report = assertIs<EmailLookup.Found>(repository.getEmail("e1")).detail.attachments.first()

            assertContentEquals("%PDF-1.7".encodeToByteArray(), repository.download(report))
        }

    @Test
    fun `an attached file is uploaded and goes out as an attachment of the message`() =
        runTest {
            val fake = server()
            val client = fake.client()
            val compose = ComposeRepository(client, MailboxRepository(client), KeyedTextResolver, TimeZone.UTC)
            val bytes = "minutes".encodeToByteArray()
            val uploaded = assertIs<UploadResult.Uploaded>(compose.upload(picked("notes.txt", bytes))).attachment
            val draft = Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "see attached", attachments = listOf(uploaded))

            val saved = assertIs<DraftSaveResult.Saved>(compose.save(draft, replacing = null))
            assertEquals(SendResult.Sent, compose.send(saved.emailId))

            val sent = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf(FakeAttachment(uploaded.blobId, "notes.txt", "text/plain", 7)), sent.attachments)
            assertContentEquals(bytes, fake.blobs.getValue(uploaded.blobId))
        }

    @Test
    fun `a file over the server's upload limit is refused before it is read`() =
        runTest {
            val fake = server()
            fake.maxSizeUpload = 10
            val client = fake.client()
            val compose = ComposeRepository(client, MailboxRepository(client), KeyedTextResolver, TimeZone.UTC)
            val huge = PickedFile("huge.bin", "application/octet-stream", 11) { fail("read a file the server would refuse") }

            assertEquals(UploadResult.TooLarge(10), compose.upload(huge))
            assertEquals(emptyMap(), fake.blobs.filterKeys { it.startsWith("blob") })
        }

    @Test
    fun `a forward carries the original's files by blob id`() =
        runTest {
            val client = server().client()
            val compose = ComposeRepository(client, MailboxRepository(client), KeyedTextResolver, TimeZone.UTC)

            val draft = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Forward("e1"))).draft

            assertEquals(listOf("b-report", "b-stray"), draft.attachments.map { it.blobId })
        }

    @Test
    fun `a saved draft reopens with its attachments`() =
        runTest {
            val client = server().client()
            val compose = ComposeRepository(client, MailboxRepository(client), KeyedTextResolver, TimeZone.UTC)
            val uploaded = assertIs<UploadResult.Uploaded>(compose.upload(picked("a.txt", "a".encodeToByteArray()))).attachment
            val saved = assertIs<DraftSaveResult.Saved>(compose.save(Draft(subject = "later", attachments = listOf(uploaded)), null))

            val reopened = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Draft(saved.emailId))).draft

            assertEquals(listOf(uploaded), reopened.attachments)
        }

    @Test
    fun `sizes read in the largest unit under 1024`() {
        assertEquals(uiText(Res.string.size_bytes, "512"), fileSize(512))
        assertEquals(uiText(Res.string.size_kilobytes, "1"), fileSize(1024))
        assertEquals(uiText(Res.string.size_kilobytes, "1.5"), fileSize(1536))
        assertEquals(uiText(Res.string.size_megabytes, "25"), fileSize(25L * 1024 * 1024))
        assertEquals(uiText(Res.string.size_gigabytes, "2048"), fileSize(2048L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a sender's file name cannot climb out of the folder it is saved in`() {
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("evil.exe", safeFileName("C:\\Users\\evil.exe"))
        assertEquals("bashrc", safeFileName(".bashrc"))
        assertEquals("attachment", safeFileName(" .. "))
        assertEquals("ab.txt", safeFileName("a\u0000b.txt"))
    }

    @Test
    fun `a name already taken gets the first free number before its extension`() {
        val taken = setOf("report.pdf", "report (1).pdf", "notes")

        assertEquals("report (2).pdf", uniqueFileName("report.pdf", taken::contains))
        assertEquals("notes (1)", uniqueFileName("notes", taken::contains))
        assertEquals("fresh.txt", uniqueFileName("fresh.txt", taken::contains))
    }

    @Test
    fun `a file's badge is its extension, else its subtype, else nothing`() {
        assertEquals("PDF", fileBadge("report.pdf", "application/octet-stream"))
        assertEquals("PNG", fileBadge(null, "image/png"))
        assertEquals(null, fileBadge("archive.backup", "application/vnd.openxmlformats-officedocument"))
    }
}
