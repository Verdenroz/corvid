package com.verdenroz.corvid.app

import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.input.key.KeyEvent
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.ui.EntryHandlers

/**
 * The unlocked back stack; each entry's saved UI state is keyed by its place in it and dropped when it is popped.
 * [onLeave] runs before a push or a tab change takes the user off the screen they were on; going back is not leaving.
 */
internal class Navigator(
    private val stack: SnapshotStateList<Route>,
    private val saved: SaveableStateHolder,
    private val onLeave: () -> Unit = {},
) {
    private val handlers = mutableMapOf<String, EntryHandlers>()

    val route: Route get() = stack.last()

    val depth: Int get() = stack.size

    val searching: Boolean get() = stack.any { it is Route.Search }

    operator fun get(index: Int): Route = stack[index]

    fun entryKey(index: Int): String = "$index ${stack[index].encode()}"

    fun handlers(index: Int): EntryHandlers = handlers.getOrPut(entryKey(index)) { EntryHandlers() }

    private val top: EntryHandlers get() = handlers(stack.lastIndex)

    /** The keys nothing focused took go to the top screen alone, never one under a sheet or panel. */
    fun handleKey(event: KeyEvent): Boolean = top.handleKey(event)

    /** Whether back belongs to the shell at all; on the Inbox with nothing pushed it leaves the app unless the screen wants it. */
    val handlesBack: Boolean get() = stack.size > 1 || route != Route.Home(HomeTab.Inbox) || top.guardsBack

    /** Back as the user pressed it: the top screen's guard answers first. */
    fun handleBack() {
        if (!top.handleBack()) back()
    }

    /** A second message being written is refused, so Write never stacks one compose on another. */
    fun push(route: Route) {
        if (route is Route.Compose && stack.any { it is Route.Compose }) return
        onLeave()
        stack += route
    }

    /** A draft opens to be written; anything else opens to be read. */
    fun openEmail(email: EmailSummary) {
        push(if (CorvidKeywords.DRAFT in email.keywords) Route.Compose(ComposeIntent.Draft(email.id)) else Route.Reader(email.id))
    }

    /** Swaps the top screen for [route], forgetting what the old one saved. */
    fun replace(route: Route) {
        forget(entryKey(stack.lastIndex))
        stack[stack.lastIndex] = route
    }

    /**
     * Searching from a search updates it in place; from anywhere else a non-blank query opens one.
     * Clearing the query leaves the search unless [keepBlank], where the search screen holds the field itself.
     */
    fun search(
        query: String,
        keepBlank: Boolean = false,
    ) {
        val text = query.trim()
        val top = route
        when {
            top is Route.Search && text.isEmpty() && !keepBlank -> back()
            top is Route.Search -> if (top.query != text) replace(Route.Search(text))
            text.isNotEmpty() -> push(Route.Search(text))
        }
    }

    fun selectTab(tab: HomeTab) {
        if (tab != homeTab) onLeave()
        stack[0] = Route.Home(tab)
    }

    /** Returns to the Inbox tab with nothing pushed, unless a message is being written, which is left on screen. */
    fun showInbox() {
        if (stack.any { it is Route.Compose }) return
        while (stack.size > 1) back()
        selectTab(HomeTab.Inbox)
    }

    fun show(request: OpenRequest) {
        when (request) {
            OpenRequest.Inbox -> showInbox()
            is OpenRequest.Digest -> open(Route.Digest(request.id))
        }
    }

    val homeTab: HomeTab get() = (stack[0] as Route.Home).tab

    /** Shows [tab] from anywhere, as the sidebar does: pushed screens close first, and one with unsaved work can stop it. */
    fun openTab(tab: HomeTab) {
        if (popTo(0)) selectTab(tab)
    }

    /** Returns to [route] if it is already on the stack rather than opening a second copy. */
    fun open(route: Route) {
        val at = stack.lastIndexOf(route)
        if (at < 0) push(route) else popTo(at)
    }

    /** Pops down to [index]; false when a screen's guard took the press and stopped it. */
    private fun popTo(index: Int): Boolean {
        while (stack.lastIndex > index) {
            if (top.handleBack()) return false
            back()
        }
        return true
    }

    /** Pops a pushed screen; from a home tab other than Inbox, returns to Inbox. */
    fun back() {
        if (stack.size > 1) {
            forget(entryKey(stack.lastIndex))
            stack.removeAt(stack.lastIndex)
        } else {
            selectTab(HomeTab.Inbox)
        }
    }

    private fun forget(key: String) {
        saved.removeState(key)
        handlers -= key
    }
}
