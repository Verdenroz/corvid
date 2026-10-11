package com.verdenroz.corvid.app

import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.verdenroz.corvid.core.ui.BackGuard

/** The unlocked back stack; each entry's saved UI state is keyed by its place in it and dropped when it is popped. */
internal class Navigator(
    private val stack: SnapshotStateList<Route>,
    private val saved: SaveableStateHolder,
) {
    private val guards = mutableMapOf<String, BackGuard>()

    val route: Route get() = stack.last()

    val depth: Int get() = stack.size

    val searching: Boolean get() = stack.any { it is Route.Search }

    operator fun get(index: Int): Route = stack[index]

    fun entryKey(index: Int): String = "$index ${stack[index].encode()}"

    fun guard(index: Int): BackGuard = guards.getOrPut(entryKey(index)) { BackGuard() }

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

    val homeTab: HomeTab get() = (stack[0] as Route.Home).tab

    /** Shows [tab] from anywhere, as the sidebar does: pushed screens close first, and one with unsaved work can stop it. */
    fun openTab(tab: HomeTab) {
        if (popTo(0)) selectTab(tab)
    }

    /** Returns to [route] if it is already on the stack rather than opening a second copy. */
    fun open(route: Route) {
        val at = (0 until stack.size).lastOrNull { stack[it] == route }
        if (at == null) push(route) else popTo(at)
    }

    /** Pops down to [index]; false when a screen's guard took the press and stopped it. */
    private fun popTo(index: Int): Boolean {
        while (stack.lastIndex > index) {
            if (guards[entryKey(stack.lastIndex)]?.handle() == true) return false
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
        guards -= key
    }
}
