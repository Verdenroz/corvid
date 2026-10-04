package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.FakeSieveScript
import com.verdenroz.corvid.core.testing.withActiveScript
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.no_trash_folder
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BASE_URL = "http://127.0.0.1:9999"

private val RULE =
    FilterRule(
        name = "Receipts",
        enabled = true,
        match = MatchMode.All,
        conditions = listOf(FilterCondition(ConditionField.From, ConditionOperator.Contains, "shop.test")),
        actions = listOf(FilterAction.AddLabel("receipts"), FilterAction.MarkRead),
        stop = false,
    )

private suspend fun repository(fake: FakeJmapServer): FiltersRepository {
    val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
    return FiltersRepository(client)
}

private fun FakeJmapServer.scriptText(name: String): String =
    blobs.getValue(sieveScripts.single { it.name == name }.blobId).decodeToString()

class FiltersRepositoryTest {
    @Test
    fun `an account with no scripts loads an empty rule list`() =
        runTest {
            val loaded = repository(FakeJmapServer()).load()

            assertEquals(FilterSet(emptyList(), null, emptyList()), loaded)
        }

    @Test
    fun `scripts and folders load in a single request`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m1", name = "Work")
            val repository = repository(fake)
            val before = fake.requestCount

            val loaded = repository.load()

            assertEquals(1, fake.requestCount - before)
            assertEquals(listOf(MailboxRef("m1", listOf("Work"))), loaded.folders)
        }

    @Test
    fun `saved rules load back exactly`() =
        runTest {
            val repository = repository(FakeJmapServer())

            repository.save(listOf(RULE), replaceForeign = false)

            assertEquals(listOf(RULE), repository.load().rules)
        }

    @Test
    fun `a save creates one active script named corvid with the rule keyword`() =
        runTest {
            val fake = FakeJmapServer()

            val result = repository(fake).save(listOf(RULE), replaceForeign = false)

            assertEquals(SaveResult.Saved, result)
            assertEquals(listOf("corvid" to true), fake.sieveScripts.map { it.name to it.isActive })
            assertTrue("addflag \"corvid-rule-receipts\";" in fake.scriptText("corvid"))
        }

    @Test
    fun `a second save updates the same script rather than adding another`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            repository.save(listOf(RULE), replaceForeign = false)

            repository.save(listOf(RULE.copy(name = "Renamed")), replaceForeign = false)

            assertEquals(1, fake.sieveScripts.size)
            assertEquals("Renamed", repository.load().rules.single().name)
        }

    @Test
    fun `an active foreign script is reported on load`() =
        runTest {
            val fake = FakeJmapServer()
            fake.withActiveScript()

            assertEquals("vacation", repository(fake).load().foreignActiveScript)
        }

    @Test
    fun `a save over an active foreign script asks for confirmation and writes nothing`() =
        runTest {
            val fake = FakeJmapServer()
            fake.withActiveScript()

            val result = repository(fake).save(listOf(RULE), replaceForeign = false)

            assertEquals(SaveResult.NeedsConfirmation("vacation"), result)
            assertEquals(listOf("vacation" to true), fake.sieveScripts.map { it.name to it.isActive })
            assertTrue(fake.blobs.isEmpty())
        }

    @Test
    fun `a confirmed save activates corvid and keeps the foreign script`() =
        runTest {
            val fake = FakeJmapServer()
            fake.withActiveScript()

            val result = repository(fake).save(listOf(RULE), replaceForeign = true)

            assertEquals(SaveResult.Saved, result)
            assertEquals(
                listOf("vacation" to false, "corvid" to true),
                fake.sieveScripts.map { it.name to it.isActive },
            )
        }

    @Test
    fun `an inactive foreign script does not block a save`() =
        runTest {
            val fake = FakeJmapServer()
            fake.sieveScripts += FakeSieveScript(id = "old", name = "vacation", blobId = "b0")

            val result = repository(fake).save(listOf(RULE), replaceForeign = false)

            assertEquals(SaveResult.Saved, result)
            assertEquals(listOf("vacation" to false, "corvid" to true), fake.sieveScripts.map { it.name to it.isActive })
        }

    @Test
    fun `a script the server rejects comes back as invalidSieve`() =
        runTest {
            val fake = FakeJmapServer()
            fake.invalidSieve = true

            val result = repository(fake).save(listOf(RULE), replaceForeign = false)

            assertEquals(UiText.FromServer("script rejected"), assertIs<SaveResult.Rejected>(result).reason)
        }

    @Test
    fun `move to trash files into the mailbox that has the trash role`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m1", name = "Bin", role = "trash")
            val rule = RULE.copy(actions = listOf(FilterAction.MoveToTrash))

            repository(fake).save(listOf(rule), replaceForeign = false)

            assertTrue("fileinto :mailboxid \"m1\" \"Trash\";" in fake.scriptText("corvid"))
        }

    @Test
    fun `move to trash with no trash mailbox is rejected and writes nothing`() =
        runTest {
            val fake = FakeJmapServer()
            val rule = RULE.copy(actions = listOf(FilterAction.MoveToTrash))

            val result = repository(fake).save(listOf(rule), replaceForeign = false)

            assertEquals(SaveResult.Rejected(uiText(Res.string.no_trash_folder)), result)
            assertTrue(fake.sieveScripts.isEmpty())
        }

    @Test
    fun `folders carry their id and a full path so same-named children differ`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m1", name = "Finance")
            fake.mailboxes += FakeMailbox(id = "m2", name = "Receipts", parentId = "m1")
            fake.mailboxes += FakeMailbox(id = "m3", name = "Receipts")

            assertEquals(
                listOf(
                    MailboxRef("m1", listOf("Finance")),
                    MailboxRef("m2", listOf("Finance", "Receipts")),
                    MailboxRef("m3", listOf("Receipts")),
                ),
                repository(fake).load().folders,
            )
        }

    @Test
    fun `a corvid script whose rules line is truncated fails to load as a format error`() =
        runTest {
            val fake = FakeJmapServer()
            fake.blobs["b0"] = "# corvid-rules v1 [{\nkeep;\n".encodeToByteArray()
            fake.sieveScripts += FakeSieveScript(id = "s0", name = "corvid", blobId = "b0")

            assertFailsWith<FiltersFormatException> { repository(fake).load() }
        }
}
