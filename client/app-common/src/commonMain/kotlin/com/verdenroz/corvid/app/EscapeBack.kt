package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/** Feeds Escape into the same back dispatcher Android's back key uses, so every back handler answers both. */
class EscapeBack {
    private val input = DirectNavigationEventInput()

    fun back() = input.backCompleted()

    fun onKey(event: KeyEvent): Boolean = (event.key == Key.Escape && event.type == KeyEventType.KeyDown).also { if (it) back() }

    @Composable
    fun Attach() {
        val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        DisposableEffect(dispatcher) {
            dispatcher?.addInput(input)
            onDispose { dispatcher?.removeInput(input) }
        }
    }
}
