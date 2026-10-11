package com.verdenroz.corvid.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.ui.GuardBack
import com.verdenroz.corvid.core.ui.LocalBackGuard
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackGuardTest {
    private fun routeAfterBack(takesBack: Boolean): Route {
        val nav = navigator(RecordingHolder(), Route.Filters)
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalBackGuard provides nav.guard(1)) { GuardBack { takesBack } }
            }
            runOnIdle { nav.handleBack() }
        }
        return nav.route
    }

    @Test
    fun `a screen whose guard takes back stays on top`() {
        assertEquals(Route.Filters, routeAfterBack(takesBack = true))
    }

    @Test
    fun `a guard that lets back through pops the screen`() {
        assertEquals(Route.Home(HomeTab.Inbox), routeAfterBack(takesBack = false))
    }

    @Test
    fun `opening a tab stops at a screen whose guard takes back`() {
        val nav = navigator(RecordingHolder(), Route.Filters)
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalBackGuard provides nav.guard(1)) { GuardBack { true } }
            }
            runOnIdle { nav.openTab(HomeTab.Sent) }
        }

        assertEquals(Route.Filters, nav.route)
        assertEquals(HomeTab.Inbox, nav.homeTab)
    }
}
