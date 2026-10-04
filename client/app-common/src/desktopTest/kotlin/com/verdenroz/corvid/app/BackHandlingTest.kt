package com.verdenroz.corvid.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackHandlingTest {
    private fun backsAfterEscape(enabled: Boolean): Int {
        var backs by mutableIntStateOf(0)
        val escape = EscapeBack()
        runComposeUiTest {
            setContent {
                escape.Attach()
                NavigationBackHandler(
                    state = rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = enabled,
                    onBackCompleted = { backs++ },
                )
            }
            runOnIdle { escape.back() }
            waitForIdle()
        }
        return backs
    }

    @Test
    fun `escape reaches an enabled back handler on desktop`() {
        assertEquals(1, backsAfterEscape(enabled = true))
    }

    @Test
    fun `escape leaves a disabled back handler alone`() {
        assertEquals(0, backsAfterEscape(enabled = false))
    }
}
