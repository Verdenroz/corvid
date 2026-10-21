package com.verdenroz.corvid.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** At or above this width the shell lays out for a window rather than a phone. */
val WIDE_LAYOUT = 720.dp

/** Set once by the shell from its own width; every screen reads it rather than measuring again. */
val LocalWideLayout = compositionLocalOf { false }

/** Touch targets stay 48dp when narrow; wide, the pointer gets the denser 40dp. */
@Composable
@ReadOnlyComposable
fun touchTarget(): Dp = if (LocalWideLayout.current) 40.dp else 48.dp
