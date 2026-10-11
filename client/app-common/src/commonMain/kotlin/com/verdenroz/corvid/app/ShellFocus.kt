package com.verdenroz.corvid.app

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent

/**
 * The shell's own focus. Screen keys fire only while the shell itself holds it, so a focused text field anywhere
 * inside keeps the letters typed into it.
 */
internal class ShellFocus {
    val requester = FocusRequester()
    private var held = false
    private var within = false

    fun modifier(onKey: (KeyEvent) -> Boolean): Modifier =
        Modifier.onFocusChanged {
            held = it.isFocused
            within = it.hasFocus
        }.focusRequester(requester)
            .focusable()
            .onKeyEvent { held && onKey(it) }

    /** A focused field that leaves with its screen takes focus with it, so each new screen takes it back if nothing holds it. */
    @Composable
    fun ReclaimOn(screen: Any) {
        LaunchedEffect(screen) { if (!within) requester.requestFocus() }
    }
}
