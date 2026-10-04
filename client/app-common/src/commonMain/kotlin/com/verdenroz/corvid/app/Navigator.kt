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

    fun selectTab(tab: HomeTab) {
        stack[0] = Route.Home(tab)
    }

    /** Pops a pushed screen; from a home tab other than Inbox, returns to Inbox. */
    fun back() {
        if (stack.size > 1) {
            val popped = entryKey(stack.lastIndex)
            saved.removeState(popped)
            guards -= popped
            stack.removeAt(stack.lastIndex)
        } else {
            selectTab(HomeTab.Inbox)
        }
    }
}
