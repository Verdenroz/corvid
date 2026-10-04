package com.verdenroz.corvid.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellBackTest {
    @Test
    fun `the shell takes back from a pushed screen or a tab other than inbox`() {
        assertEquals(true, shellHandlesBack(Route.Reader("m1"), depth = 2))
        assertEquals(true, shellHandlesBack(Route.Home(HomeTab.Sent), depth = 1))
    }

    @Test
    fun `back on the inbox root leaves the app to the platform`() {
        assertEquals(false, shellHandlesBack(Route.Home(HomeTab.Inbox), depth = 1))
    }
}
