package com.verdenroz.corvid.app

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.verdenroz.corvid.app.shell.KeyState
import com.verdenroz.corvid.app.shell.NavDrawer
import com.verdenroz.corvid.app.shell.sidebarPlace
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.ui.LocalWideLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class NavDrawerTest {
    private val fake =
        FakeJmapServer().apply {
            mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            mailboxes += FakeMailbox(id = "trash", name = "Trash", role = "trash")
            emails += FakeEmail("i", "i", setOf("inbox"), "kept subject", "ada@example.test", "2024-01-02T00:00:00Z", fromName = "Ada")
            emails += FakeEmail("t", "t", setOf("trash"), "binned subject", "bo@example.test", "2024-01-01T00:00:00Z", fromName = "Bo")
        }
    private val ledgers = Ledgers(runBlocking { MailboxRepository(fake.client()) })
    private val escape = EscapeBack()
    private val drawer = DrawerState(DrawerValue.Closed)
    private var shellBacks = 0
    private lateinit var navigator: Navigator

    @AfterTest
    fun clear() = ledgers.clear()

    private val menu =
        SemanticsMatcher("opens the drawer") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open folders" }

    private fun narrowHome(check: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 860) {
            setContent {
                val saved = rememberSaveableStateHolder()
                navigator = remember { Navigator(mutableStateListOf(Route.Home(HomeTab.Inbox)), saved) }
                val scope = rememberCoroutineScope()
                val key = remember { KeyState("n1", MutableStateFlow(null), 5.minutes) {} }
                escape.Attach()
                NavigationBackHandler(
                    state = rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = true,
                    onBackCompleted = { shellBacks++ },
                )
                CorvidTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalWideLayout provides false) {
                        NavDrawer(
                            state = drawer,
                            enabled = navigator.route is Route.Home,
                            place = sidebarPlace(navigator.homeTab, navigator.route),
                            inboxUnread = 1,
                            onSelect = navigator::openTab,
                            onFilters = {},
                            onSettings = {},
                        ) {
                            Home(Route.Home(navigator.homeTab), key, ShellState(), navigator, ledgers) { scope.launch { drawer.open() } }
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { shows("kept subject") }
            onNode(menu).performClick()
            waitUntil(timeoutMillis = 5_000) { drawer.currentValue == DrawerValue.Open && !drawer.isAnimationRunning }
            check()
        }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `choosing Trash in the drawer closes it and shows the Trash list`() {
        narrowHome {
            onAllNodesWithText("Trash")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { drawer.isClosed && !drawer.isAnimationRunning && shows("binned subject") }
            onNodeWithText("binned subject").assertIsDisplayed()
            assertFalse(shows("kept subject"))
            assertEquals(HomeTab.Trash, navigator.homeTab)
        }
    }

    @Test
    fun `back closes an open drawer before the shell sees it`() {
        narrowHome {
            runOnIdle { escape.back() }
            waitUntil(timeoutMillis = 5_000) { drawer.isClosed && !drawer.isAnimationRunning }
            assertEquals(0, shellBacks)
            assertEquals(HomeTab.Inbox, navigator.homeTab)
        }
    }
}
