package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent

/**
 * The shell gives each back-stack entry one and offers the top entry's handler only the keys that reach the shell
 * itself, so a focused text field keeps every key it is typed with.
 */
class PageKeys {
    private var onKey: ((KeyEvent) -> Boolean)? = null

    /** True when the screen took the key. */
    fun handle(event: KeyEvent): Boolean = onKey?.invoke(event) == true

    internal fun set(handler: ((KeyEvent) -> Boolean)?) {
        onKey = handler
    }
}

val LocalPageKeys = compositionLocalOf<PageKeys?> { null }

/** Gives a screen the shell's spare keys while it is on top; [onKey] returns true when it took one. */
@Composable
fun HandleKeys(onKey: (KeyEvent) -> Boolean) {
    val keys = LocalPageKeys.current ?: return
    val current by rememberUpdatedState(onKey)
    DisposableEffect(keys) {
        keys.set { current(it) }
        onDispose { keys.set(null) }
    }
}
