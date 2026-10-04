package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.model.ComposeIntent
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {
    @Test
    fun `every route survives a save and restore unchanged`() {
        val routes =
            listOf(
                Route.Home(HomeTab.Inbox),
                Route.Home(HomeTab.Drafts),
                Route.Filters,
                Route.Reader("e1"),
                Route.Compose(ComposeIntent.New),
                Route.Compose(ComposeIntent.To("ada@x.test")),
                Route.Compose(ComposeIntent.To("leave@list.test", "unsubscribe? now")),
                Route.Sender("ada@x.test"),
                Route.Search(""),
                Route.Search("q: \"2 of 3\" / ~"),
                Route.Compose(ComposeIntent.Reply("e1", all = false)),
                Route.Compose(ComposeIntent.Reply("e1", all = true)),
                Route.Compose(ComposeIntent.Forward("e1")),
            )

        assertEquals(routes, routes.map { decodeRoute(it.encode()) })
    }

    @Test
    fun `an email id containing a colon is kept whole`() {
        assertEquals(Route.Reader("a:b"), decodeRoute(Route.Reader("a:b").encode()))
    }
}
