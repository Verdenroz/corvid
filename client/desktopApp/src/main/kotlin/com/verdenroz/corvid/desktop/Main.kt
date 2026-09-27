package com.verdenroz.corvid.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.verdenroz.corvid.app.CorvidApp
import com.verdenroz.corvid.app.di.appModules
import org.koin.core.context.startKoin

fun main() {
    startKoin { modules(appModules) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Corvid") {
            CorvidApp()
        }
    }
}
