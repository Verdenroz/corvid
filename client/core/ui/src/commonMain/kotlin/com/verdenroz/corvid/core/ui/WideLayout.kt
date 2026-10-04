package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** At or above this width the shell lays out for a window rather than a phone. */
val WIDE_LAYOUT = 720.dp

/** Set once by the shell from its own width; every screen reads it rather than measuring again. */
val LocalWideLayout = compositionLocalOf { false }
