package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.ui.EntryHandlers
import com.verdenroz.corvid.core.ui.LocalEntryHandlers
import com.verdenroz.corvid.core.ui.LocalTopEntryKeys
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SEEN = setOf("\$seen")

@OptIn(ExperimentalTestApi::class)
class LedgerInteractionTest {
    private val store = ViewModelStore()
    private val fake =
        FakeJmapServer().apply {
            mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            mailboxes += FakeMailbox(id = "archive", name = "Archive", role = "archive")
            mailboxes += FakeMailbox(id = "trash", name = "Trash", role = "trash")
            emails += FakeEmail("a", "a", setOf("inbox"), "subject a", "ada@example.test", "2024-01-03T00:00:00Z", fromName = "Ada")
            emails += FakeEmail("b", "b", setOf("inbox"), "subject b", "bo@example.test", "2024-01-02T00:00:00Z", SEEN, fromName = "Bo")
            emails += FakeEmail("c", "c", setOf("inbox"), "subject c", "cy@example.test", "2024-01-01T00:00:00Z", fromName = "Cy")
        }
    private val repository = runBlocking { MailboxRepository(fake.client()) }
    private val ledger = store.model("ledger") { LedgerViewModel(repository, LedgerTab.Inbox) }
    private val actions = store.model("actions") { MailActions(repository) }
    private val opened = mutableListOf<String>()
    private val senders = mutableListOf<String>()
    private var shellBacks = 0
    private val handlers = EntryHandlers()

    private fun <T : ViewModel> ViewModelStore.model(
        key: String,
        create: () -> T,
    ): T = create().also { put(key, it) }

    @AfterTest
    fun clear() = store.clear()

    /** Escape feeds the back dispatcher, as the desktop window does, and back and keys reach the list through its entry, as in the shell. */
    private fun ComposeUiTest.showLedger(
        wide: Boolean,
        overnight: OvernightOffer? = null,
        above: @Composable () -> Unit = {},
    ) {
        val escape = DirectNavigationEventInput()
        setContent {
            val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher?.addInput(escape)
                onDispose { dispatcher?.removeInput(escape) }
            }
            NavigationBackHandler(
                rememberNavigationEventState(NavigationEventInfo.None),
                isBackEnabled = true,
                onBackCompleted = { if (!handlers.handleBack()) shellBacks++ },
            )
            CorvidTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalWideLayout provides wide,
                    LocalEntryHandlers provides handlers,
                    LocalTopEntryKeys provides handlers::handleKey,
                ) {
                    Column(
                        Modifier.onPreviewKeyEvent { event ->
                            (event.key == Key.Escape && event.type == KeyEventType.KeyDown).also { if (it) escape.backCompleted() }
                        },
                    ) {
                        above()
                        LedgerContent(
                            ledger,
                            actions,
                            "empty",
                            { opened += it.id },
                            { senders += it },
                            takeFocus = true,
                            overnight = overnight,
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllRows() }
    }

    private fun ComposeUiTest.onAllRows(): Boolean =
        listOf("a", "b", "c").all { runCatching { onNodeWithText("subject $it", substring = true).fetchSemanticsNode() }.isSuccess }

    private fun ComposeUiTest.row(id: String) = onNodeWithText("subject $id", substring = true)

    private fun ComposeUiTest.press(key: Key) = onRoot().performKeyInput { pressKey(key) }

    private fun ComposeUiTest.pressShifted(key: Key) = onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(key) } }

    private fun ComposeUiTest.waitForServer(condition: () -> Boolean) = waitUntil(timeoutMillis = 5_000, condition = condition)

    @Test
    fun `the overnight block names the digest and opens it or hides it`() =
        runDesktopComposeUiTest {
            var opens = 0
            var hides = 0
            val entry = OvernightEntry("d1", 3, listOf("Marta"), 2)
            showLedger(wide = false, overnight = OvernightOffer(entry, onOpen = { opens++ }, onHide = { hides++ }))

            onNodeWithText("Marta wrote;").assertExists()
            onNodeWithText("2 from lists", substring = true).assertExists()
            onNodeWithText("Overnight").performClick()
            onNodeWithContentDescription("Hide").performClick()

            assertEquals(1, opens)
            assertEquals(1, hides)
        }

    @Test
    fun `j and k move keyboard focus down and up the rows`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            press(Key.J)
            row("a").assertIsFocused()
            press(Key.J)
            row("b").assertIsFocused()
            press(Key.K)
            row("a").assertIsFocused()
        }

    @Test
    fun `x ticks the focused row and Escape clears every tick before it goes back`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            press(Key.J)
            press(Key.X)
            waitForIdle()
            assertEquals(setOf("a"), ledger.selected.value)

            press(Key.Escape)
            waitForIdle()
            assertTrue(ledger.selected.value.isEmpty())
            assertEquals(0, shellBacks)

            press(Key.Escape)
            waitForIdle()
            assertEquals(1, shellBacks)
        }

    @Test
    fun `enter and o open the focused row`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            press(Key.J)
            press(Key.O)
            press(Key.J)
            press(Key.Enter)
            waitForIdle()

            assertEquals(listOf("a", "b"), opened)
        }

    @Test
    fun `e archives the focused inbox row and focus moves to the next`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            press(Key.J)
            press(Key.E)

            waitForServer { fake.emails.single { it.id == "a" }.mailboxIds == setOf("archive") }
            row("b").assertIsFocused()
        }

    @Test
    fun `hash moves the focused row to trash`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            press(Key.J)
            pressShifted(Key.Three)

            waitForServer { fake.emails.single { it.id == "a" }.mailboxIds == setOf("trash") }
        }

    @Test
    fun `s stars the focused row`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            press(Key.J)
            press(Key.S)

            waitForServer { "\$flagged" in fake.emails.single { it.id == "a" }.keywords }
        }

    @Test
    fun `shift i marks the focused row read and shift u marks a read one unread`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            press(Key.J)
            pressShifted(Key.I)
            waitForServer { "\$seen" in fake.emails.single { it.id == "a" }.keywords }
            press(Key.J)
            pressShifted(Key.U)
            waitForServer { "\$seen" !in fake.emails.single { it.id == "b" }.keywords }
        }

    @Test
    fun `keys typed into a text field never reach the list`() =
        runDesktopComposeUiTest {
            showLedger(wide = true) {
                var text by remember { mutableStateOf("") }
                BasicTextField(text, { text = it }, Modifier.testTag("field"))
            }

            onNodeWithTag("field").performClick()
            onNodeWithTag("field").performTextInput("jx")
            onNodeWithTag("field").performKeyInput { pressKey(Key.J) }
            waitForIdle()

            row("a").assertIsNotFocused()
            assertTrue(ledger.selected.value.isEmpty())
        }

    @Test
    fun `a long press on the sender name selects the row on narrow`() =
        runDesktopComposeUiTest {
            showLedger(wide = false)

            onNodeWithText("Ada", useUnmergedTree = true).performTouchInput { longClick() }
            waitForIdle()

            assertEquals(setOf("a"), ledger.selected.value)
            assertTrue(senders.isEmpty())
        }

    @Test
    fun `a long press on the sender name selects the row on wide`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            onNodeWithText("Ada", useUnmergedTree = true).performTouchInput { longClick() }
            waitForIdle()

            assertEquals(setOf("a"), ledger.selected.value)
            assertTrue(senders.isEmpty())
        }

    @Test
    fun `while selecting a tap on a sender name ticks that row instead of opening the card`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            onNodeWithText("Ada", useUnmergedTree = true).performTouchInput { longClick() }
            onNodeWithText("Bo", useUnmergedTree = true).performTouchInput { click() }
            waitForIdle()

            assertEquals(setOf("a", "b"), ledger.selected.value)
            assertTrue(senders.isEmpty())
        }

    @Test
    fun `on wide a click on the sender name opens the card`() =
        runDesktopComposeUiTest {
            showLedger(wide = true)

            onNodeWithText("Ada", useUnmergedTree = true).performTouchInput { click() }
            waitForIdle()

            assertEquals(listOf("ada@example.test"), senders)
            assertTrue(ledger.selected.value.isEmpty())
        }

    @Test
    fun `on narrow a tap on the name opens the conversation, not the card`() =
        runDesktopComposeUiTest {
            showLedger(wide = false)

            onNodeWithText("Ada", useUnmergedTree = true).performTouchInput { click() }
            waitForIdle()

            assertEquals(listOf("a"), opened)
            assertTrue(senders.isEmpty())
        }
}
