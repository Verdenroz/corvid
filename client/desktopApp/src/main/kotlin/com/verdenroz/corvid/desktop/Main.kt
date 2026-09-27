package com.verdenroz.corvid.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.verdenroz.corvid.app.CorvidApp
import com.verdenroz.corvid.app.di.appModules
import com.verdenroz.corvid.core.auth.InMemoryPreferencesFactory
import com.verdenroz.corvid.core.auth.platformAuthModule
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main() {
    // Must win the race against oidc-appsupport's own PreferencesFactory
    // before any sign-in starts, or its flow state lands in a file in $HOME.
    runBlocking { InMemoryPreferencesFactory.seed() }
    startKoin { modules(appModules + platformAuthModule) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Corvid") {
            CorvidApp()
        }
    }
}
