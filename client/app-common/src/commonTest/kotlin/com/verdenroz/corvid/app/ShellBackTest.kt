package com.verdenroz.corvid.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellBackTest {
    @Test
    fun `the shell takes back from a pushed screen or a tab other than inbox`() {
        assertEquals(true, navigator(RecordingHolder(), Route.Reader("m1")).handlesBack)
        assertEquals(true, navigator(RecordingHolder()).apply { selectTab(HomeTab.Sent) }.handlesBack)
    }

    @Test
    fun `back on the inbox root leaves the app to the platform`() {
        assertEquals(false, navigator(RecordingHolder()).handlesBack)
    }
}
