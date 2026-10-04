package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import com.verdenroz.corvid.core.model.ComposeIntent
import kotlin.test.Test
import kotlin.test.assertEquals

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
) = Navigator(mutableStateListOf(Route.Home(HomeTab.Inbox), *routes), holder)

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
}
