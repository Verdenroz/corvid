package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.verdenroz.corvid.core.ui.HandleKeys
import com.verdenroz.corvid.core.ui.LocalPageKeys
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShellKeysTest {
    private class Rig {
        val pressed = mutableListOf<Key>()
        var field by mutableStateOf(true)
        var top by mutableStateOf(0)
    }

    private fun shell(
        rig: Rig,
        check: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest {
        setContent {
            val saved = rememberSaveableStateHolder()
            val navigator = remember { Navigator(mutableStateListOf(Route.Home(HomeTab.Inbox), Route.Reader("a")), saved) }
            val focus = remember { ShellFocus() }
            focus.ReclaimOn(rig.top)
            Column(focus.modifier(navigator::handleKey)) {
                CompositionLocalProvider(LocalPageKeys provides navigator.keys(1)) {
                    HandleKeys { event -> (event.type == KeyEventType.KeyDown).also { if (it) rig.pressed += event.key } }
                    if (rig.field) BasicTextField("", {}, Modifier.testTag("field"))
                }
            }
        }
        waitForIdle()
        check()
    }

    @Test
    fun `a letter reaches the top screen while the shell holds focus`() {
        val rig = Rig()
        shell(rig) {
            onRoot().performKeyInput { pressKey(Key.E) }
            waitForIdle()
        }
        assertEquals(listOf(Key.E), rig.pressed)
    }

    @Test
    fun `a letter typed into a focused field never reaches the screen`() {
        val rig = Rig()
        shell(rig) {
            onNodeWithTag("field").performClick()
            onNodeWithTag("field").performKeyInput { pressKey(Key.E) }
            waitForIdle()
        }
        assertEquals(emptyList(), rig.pressed)
    }

    @Test
    fun `the shell takes focus back when the focused field leaves with its screen`() {
        val rig = Rig()
        shell(rig) {
            onNodeWithTag("field").performClick()
            rig.field = false
            rig.top = 1
            waitForIdle()
            onRoot().performKeyInput { pressKey(Key.J) }
            waitForIdle()
        }
        assertEquals(listOf(Key.J), rig.pressed)
    }
}
