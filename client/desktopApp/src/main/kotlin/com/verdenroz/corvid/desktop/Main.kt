package com.verdenroz.corvid.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.verdenroz.corvid.app.BackgroundDropController
import com.verdenroz.corvid.app.CorvidApp
import com.verdenroz.corvid.app.di.appModules
import com.verdenroz.corvid.core.auth.InMemoryPreferencesFactory
import com.verdenroz.corvid.core.auth.platformAuthModule
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import org.koin.core.context.startKoin

fun main() {
    // Must win the race against oidc-appsupport's own PreferencesFactory
    // before any sign-in starts, or its flow state lands in a file in $HOME.
    runBlocking { InMemoryPreferencesFactory.seed() }
    startKoin { modules(appModules + platformAuthModule) }
    application {
        val windowState = rememberWindowState()
        Window(onCloseRequest = ::exitApplication, title = "Corvid", state = windowState) {
            // Desktop's closest equivalent to ON_STOP: minimising is the only
            // "backgrounded" signal this platform actually has.
            val backgroundDropController: BackgroundDropController = koinInject()
            LaunchedEffect(windowState.isMinimized) {
                if (windowState.isMinimized) backgroundDropController.enteredBackground() else backgroundDropController.enteredForeground()
            }
            CorvidApp()
        }
    }
}
