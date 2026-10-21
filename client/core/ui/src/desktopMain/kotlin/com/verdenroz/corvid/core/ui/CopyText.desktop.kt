package com.verdenroz.corvid.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry(StringSelection(text))
