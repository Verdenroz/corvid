package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.feature.signin.SignInScreen

/**
 * The lock gate (slice 6) will replace this body with a NavHost switched by
 * KeyHolder state. Until then sign-in is the only route there is to reach.
 */
@Composable
fun CorvidApp() {
    CorvidTheme {
        SignInScreen()
    }
}
