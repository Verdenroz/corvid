package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import com.verdenroz.corvid.app.shell.SidebarPlace
import com.verdenroz.corvid.app.shell.sidebarPlace
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

internal class RecordingHolder : SaveableStateHolder {
    val removed = mutableListOf<Any>()

    @Composable
    override fun SaveableStateProvider(
        key: Any,
        content: @Composable () -> Unit,
    ) = content()

    override fun removeState(key: Any) {
        removed += key
    }
}

internal fun navigator(
    holder: SaveableStateHolder,
    vararg routes: Route,
    onLeave: () -> Unit = {},
) = Navigator(mutableStateListOf(Route.Home(HomeTab.Inbox), *routes), holder, onLeave)

private fun email(
    id: String,
    keywords: Set<String> = emptySet(),
) = EmailSummary(
    id,
    id,
    "subject",
    null,
    Instant.fromEpochSeconds(0),
    keywords,
    "",
    Provenance(Seal.Unsigned, null, SenderKind.Person, emptyList()),
)

class NavigatorTest {
    @Test
    fun `a popped screen's saved state is forgotten, so pushing it again starts blank`() {
        val holder = RecordingHolder()
        val nav = navigator(holder, Route.Compose(ComposeIntent.New))
        val composeKey = nav.entryKey(1)

        nav.back()
        nav.push(Route.Compose(ComposeIntent.New))

        assertEquals(listOf<Any>(composeKey), holder.removed)
        assertEquals(composeKey, nav.entryKey(1))
    }

    @Test
    fun `back from another home tab returns to the inbox without forgetting that tab`() {
        val holder = RecordingHolder()
        val nav = navigator(holder)
        nav.selectTab(HomeTab.Sent)

        nav.handleBack()

        assertEquals(Route.Home(HomeTab.Inbox), nav.route)
        assertEquals(emptyList(), holder.removed)
    }

    @Test
    fun `replacing the top screen forgets its saved state and keeps the stack depth`() {
        val holder = RecordingHolder()
        val nav = navigator(holder, Route.Search("a"))
        val searchKey = nav.entryKey(1)

        nav.replace(Route.Search("ab"))

        assertEquals(Route.Search("ab"), nav.route)
        assertEquals(2, nav.depth)
        assertEquals(listOf<Any>(searchKey), holder.removed)
    }

    @Test
    fun `searching from a home tab opens a search and a blank query opens nothing`() {
        val nav = navigator(RecordingHolder())

        nav.search("  ")
        assertEquals(1, nav.depth)
        nav.search(" invoice ")

        assertEquals(Route.Search("invoice"), nav.route)
        assertEquals(2, nav.depth)
    }

    @Test
    fun `searching again from a search replaces it, so one back leaves search`() {
        val nav = navigator(RecordingHolder(), Route.Search("inv"))

        nav.search("invoice")
        nav.search("invoice")

        assertEquals(Route.Search("invoice"), nav.route)
        assertEquals(2, nav.depth)
    }

    @Test
    fun `clearing a search goes back to the list it was opened from`() {
        val nav = navigator(RecordingHolder(), Route.Search("invoice"))

        nav.search("")

        assertEquals(Route.Home(HomeTab.Inbox), nav.route)
    }

    @Test
    fun `opening a tab from a pushed screen closes it and forgets what it saved`() {
        val holder = RecordingHolder()
        val nav = navigator(holder, Route.Reader("m1"))
        val readerKey = nav.entryKey(1)

        nav.openTab(HomeTab.Junk)

        assertEquals(listOf<Route>(Route.Home(HomeTab.Junk)), (0 until nav.depth).map(nav::get))
        assertEquals(listOf<Any>(readerKey), holder.removed)
    }

    @Test
    fun `opening a page already on the stack returns to it rather than stacking a second`() {
        val nav = navigator(RecordingHolder(), Route.Settings, Route.Notices)

        nav.open(Route.Settings)

        assertEquals(Route.Settings, nav.route)
        assertEquals(2, nav.depth)
    }

    @Test
    fun `the sidebar marks the page at its foot, and the tab underneath anything else`() {
        assertEquals(SidebarPlace.Settings, sidebarPlace(HomeTab.Inbox, Route.Notices))
        assertEquals(SidebarPlace.Filters, sidebarPlace(HomeTab.Inbox, Route.Filters))
        assertEquals(SidebarPlace.Tab(HomeTab.Junk), sidebarPlace(HomeTab.Junk, Route.Reader("m1")))
    }

    @Test
    fun `clearing a search that holds its own field keeps it open and empty`() {
        val nav = navigator(RecordingHolder(), Route.Search("invoice"))

        nav.search("", keepBlank = true)

        assertEquals(Route.Search(""), nav.route)
    }

    @Test
    fun `showing the inbox pops every pushed screen and leaves another tab`() {
        val holder = RecordingHolder()
        val nav = navigator(holder, Route.Settings, Route.Notices)
        nav.selectTab(HomeTab.Sent)

        nav.showInbox()

        assertEquals(Route.Home(HomeTab.Inbox), nav.route)
        assertEquals(1, nav.depth)
        assertEquals(2, holder.removed.size)
    }

    @Test
    fun `showing the inbox leaves a message being written on screen`() {
        val nav = navigator(RecordingHolder(), Route.Reader("e1"), Route.Compose(ComposeIntent.New))

        nav.showInbox()

        assertEquals(Route.Compose(ComposeIntent.New), nav.route)
        assertEquals(3, nav.depth)
    }

    @Test
    fun `a second message to write is refused while one is open`() {
        val nav = navigator(RecordingHolder(), Route.Compose(ComposeIntent.New))

        nav.push(Route.Compose(ComposeIntent.Reply("e1", all = false)))

        assertEquals(Route.Compose(ComposeIntent.New), nav.route)
        assertEquals(2, nav.depth)
    }

    @Test
    fun `a draft opens to be written and any other message to be read`() {
        val nav = navigator(RecordingHolder())

        nav.openEmail(email("d1", setOf(CorvidKeywords.DRAFT)))
        assertEquals(Route.Compose(ComposeIntent.Draft("d1")), nav.route)
        nav.back()
        nav.openEmail(email("m1"))

        assertEquals(Route.Reader("m1"), nav.route)
    }

    @Test
    fun `opening a screen or another tab leaves the one shown, and going back does not`() {
        var left = 0
        val nav = navigator(RecordingHolder(), onLeave = { left++ })

        nav.push(Route.Reader("m1"))
        assertEquals(1, left)
        nav.back()
        nav.selectTab(HomeTab.Inbox)
        assertEquals(1, left)
        nav.openTab(HomeTab.Sent)

        assertEquals(2, left)
    }
}
