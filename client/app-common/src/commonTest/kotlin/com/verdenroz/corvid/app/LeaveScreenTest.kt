package com.verdenroz.corvid.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaveScreenTest {
    private val inbox = Place(1, Route.Home(HomeTab.Inbox))

    @Test
    fun `opening a screen over the list leaves it`() {
        assertTrue(leftScreen(inbox, inbox.copy(depth = 2)))
    }

    @Test
    fun `switching tab leaves the list`() {
        assertTrue(leftScreen(inbox, Place(1, Route.Home(HomeTab.Sent))))
    }

    @Test
    fun `coming back to the list does not leave it`() {
        assertFalse(leftScreen(inbox.copy(depth = 2), inbox))
    }

    @Test
    fun `staying put does not leave the list`() {
        assertFalse(leftScreen(inbox, inbox))
    }
}
