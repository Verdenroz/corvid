package com.verdenroz.corvid.app

import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.input.key.KeyEvent
import com.verdenroz.corvid.core.ui.BackGuard
import com.verdenroz.corvid.core.ui.PageKeys

/** The unlocked back stack; each entry's saved UI state is keyed by its place in it and dropped when it is popped. */
internal class Navigator(
    private val stack: SnapshotStateList<Route>,
    private val saved: SaveableStateHolder,
) {
    private val guards = mutableMapOf<String, BackGuard>()
    private val keys = mutableMapOf<String, PageKeys>()

    val route: Route get() = stack.last()

    val depth: Int get() = stack.size

    val searching: Boolean get() = stack.any { it is Route.Search }

    operator fun get(index: Int): Route = stack[index]

    fun entryKey(index: Int): String = "$index ${stack[index].encode()}"

    fun guard(index: Int): BackGuard = guards.getOrPut(entryKey(index)) { BackGuard() }

    fun keys(index: Int): PageKeys = keys.getOrPut(entryKey(index)) { PageKeys() }

    /** The keys nothing focused took go to the top screen alone, never one under a sheet or panel. */
    fun handleKey(event: KeyEvent): Boolean = keys[entryKey(stack.lastIndex)]?.handle(event) == true

    /** Back as the user pressed it: the top screen's guard answers first. */
    fun handleBack() {
        if (guards[entryKey(stack.lastIndex)]?.handle() != true) back()
    }

    fun push(route: Route) {
        stack += route
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
        stack[0] = Route.Home(tab)
    }

    /** Returns to the Inbox tab with nothing pushed, unless a message is being written, which is left on screen. */
    fun showInbox() {
        if (stack.any { it is Route.Compose }) return
        while (stack.size > 1) back()
        selectTab(HomeTab.Inbox)
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
        guards -= key
        keys -= key
    }
}
