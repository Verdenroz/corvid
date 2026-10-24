package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/** Puts plain text on the system clipboard. */
@Composable
fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) { { text -> scope.launch { clipboard.setClipEntry(plainTextClip(text)) } } }
}

internal expect fun plainTextClip(text: String): ClipEntry
