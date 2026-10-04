package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** The shell gives each back-stack entry one; it asks the top entry's guard before popping it. */
class BackGuard {
    private var onBack: (() -> Boolean)? = null

    /** True when the screen took the back press itself, so the shell must not pop it. */
    fun handle(): Boolean = onBack?.invoke() == true

    internal fun set(handler: (() -> Boolean)?) {
        onBack = handler
    }
}

val LocalBackGuard = compositionLocalOf<BackGuard?> { null }

/** Lets a screen with unsaved work answer back first; [onBack] returns true when it took the press. */
@Composable
fun GuardBack(onBack: () -> Boolean) {
    val guard = LocalBackGuard.current ?: return
    val current by rememberUpdatedState(onBack)
    DisposableEffect(guard) {
        guard.set { current() }
        onDispose { guard.set(null) }
    }
}
