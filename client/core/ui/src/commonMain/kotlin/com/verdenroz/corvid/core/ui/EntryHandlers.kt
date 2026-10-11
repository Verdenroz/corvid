package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent

/**
 * What one back-stack entry's screen answers itself. The shell gives each entry one and asks only the top entry's,
 * so a screen under a sheet or panel never sees Back or a key meant for what is over it.
 */
class EntryHandlers {
    private var onBack by mutableStateOf<(() -> Boolean)?>(null)
    private var onKey: ((KeyEvent) -> Boolean)? = null

    /** True while the screen wants Back, so the shell takes it even where Back would otherwise leave the app. */
    val guardsBack: Boolean get() = onBack != null

    /** True when the screen took the back press itself, so the shell must not pop it. */
    fun handleBack(): Boolean = onBack?.invoke() == true

    /** True when the screen took the key. */
    fun handleKey(event: KeyEvent): Boolean = onKey?.invoke(event) == true

    @Composable
    internal fun Back(
        enabled: Boolean,
        handler: () -> Boolean,
    ) {
        val current by rememberUpdatedState(handler)
        DisposableEffect(this, enabled) {
            onBack = if (enabled) ({ current() }) else null
            onDispose { onBack = null }
        }
    }

    @Composable
    internal fun Keys(handler: (KeyEvent) -> Boolean) {
        val current by rememberUpdatedState(handler)
        DisposableEffect(this) {
            onKey = { current(it) }
            onDispose { onKey = null }
        }
    }
}

val LocalEntryHandlers = compositionLocalOf<EntryHandlers?> { null }

/** The shell's own key dispatch to the top entry; a focusable region that is not a text field passes its keys here. */
val LocalTopEntryKeys = staticCompositionLocalOf<(KeyEvent) -> Boolean> { { false } }

/** Lets a screen answer back first while [enabled]; [onBack] returns true when it took the press. */
@Composable
fun GuardBack(
    enabled: Boolean = true,
    onBack: () -> Boolean,
) {
    LocalEntryHandlers.current?.Back(enabled, onBack)
}

/** Gives a screen the shell's spare keys while it is on top; [onKey] returns true when it took one. */
@Composable
fun HandleKeys(onKey: (KeyEvent) -> Boolean) {
    LocalEntryHandlers.current?.Keys(onKey)
}
