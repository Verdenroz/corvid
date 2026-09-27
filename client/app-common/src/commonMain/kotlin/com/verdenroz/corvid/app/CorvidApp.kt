package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.LockedPane

/**
 * The lock gate (M1 slice 6) will replace this body with a NavHost switched
 * by KeyHolder state. Until then every route is unreachable, so Locked is
 * the only pane there is to show.
 */
@Composable
fun CorvidApp() {
    CorvidTheme {
        LockedPane()
    }
}
