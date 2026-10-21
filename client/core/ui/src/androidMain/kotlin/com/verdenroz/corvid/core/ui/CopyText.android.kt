package com.verdenroz.corvid.core.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.toClipEntry

internal actual fun plainTextClip(text: String): ClipEntry = ClipData.newPlainText(null, text).toClipEntry()
