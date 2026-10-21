package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.ui.HandleKeys
import com.verdenroz.corvid.core.ui.LocalEntryHandlers
import com.verdenroz.corvid.core.ui.LocalTopEntryKeys
import com.verdenroz.corvid.feature.mailbox.LedgerScreen
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SheetKeysTest {
    private val fake =
        FakeJmapServer().apply {
            mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            mailboxes += FakeMailbox(id = "archive", name = "Archive", role = "archive")
            emails += FakeEmail("a", "a", setOf("inbox"), "subject a", "ada@example.test", "2024-01-02T00:00:00Z", fromName = "Ada")
            emails += FakeEmail("b", "b", setOf("inbox"), "subject b", "bo@example.test", "2024-01-01T00:00:00Z", fromName = "Bo")
        }
    private val ledgers = Ledgers(runBlocking { MailboxRepository(fake.client()) })
    private val sheetKeys = mutableListOf<Key>()
    private lateinit var navigator: Navigator

    @AfterTest
    fun clear() = ledgers.clear()

    /** The list with whatever is pushed drawn over it, as wide shows a sender sheet, and keys dispatched as the shell does. */
    private fun ComposeUiTest.listUnderSheets() {
        setContent {
            val saved = rememberSaveableStateHolder()
            navigator = remember { Navigator(mutableStateListOf(Route.Home(HomeTab.Inbox)), saved, ledgers::leave) }
            val focus = remember { ShellFocus() }
            CorvidTheme(darkTheme = false) {
                CompositionLocalProvider(LocalWideLayout provides true, LocalTopEntryKeys provides navigator::handleKey) {
                    Column(focus.modifier(navigator::handleKey)) {
                        (0 until navigator.depth).forEach { index ->
                            key(navigator.entryKey(index)) {
                                CompositionLocalProvider(LocalEntryHandlers provides navigator.handlers(index)) {
                                    if (index == 0) {
                                        LedgerScreen(ledgers.of(LedgerTab.Inbox), ledgers.actions, LedgerTab.Inbox, {}, {})
                                    } else {
                                        HandleKeys { event -> (event.type == KeyEventType.KeyDown).also { if (it) sheetKeys += event.key } }
                                        Text("sheet")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("subject b", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun mailboxOf(id: String) = fake.emails.single { it.id == id }.mailboxIds

    @Test
    fun `with a sender sheet over the list, a key goes to the sheet and leaves the focused row alone`() =
        runDesktopComposeUiTest {
            listUnderSheets()
            press(Key.J)
            onNodeWithText("subject a", substring = true).assertIsFocused()

            runOnIdle { navigator.push(Route.Sender("ada@example.test")) }
            press(Key.E)

            assertEquals(listOf(Key.E), sheetKeys)
            assertEquals(setOf("inbox"), mailboxOf("a"))

            runOnIdle { navigator.back() }
            press(Key.E)
            waitUntil(timeoutMillis = 5_000) { mailboxOf("a") == setOf("archive") }
        }
}
