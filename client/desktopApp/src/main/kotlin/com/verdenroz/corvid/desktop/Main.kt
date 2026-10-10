package com.verdenroz.corvid.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.verdenroz.corvid.app.BackgroundDropController
import com.verdenroz.corvid.app.CorvidApp
import com.verdenroz.corvid.app.EscapeBack
import com.verdenroz.corvid.app.NoticeNotifier
import com.verdenroz.corvid.app.di.appModules
import com.verdenroz.corvid.core.auth.InMemoryPreferencesFactory
import com.verdenroz.corvid.core.auth.platformAuthModule
import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.discovery.platformDiscoveryModule
import com.verdenroz.corvid.core.prefs.platformPreferencesModule
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.dsl.module
import javax.imageio.ImageIO

fun main() {
    // Must win the race against oidc-appsupport's own PreferencesFactory
    // before any sign-in starts, or its flow state lands in a file in $HOME.
    runBlocking { InMemoryPreferencesFactory.seed() }
    val notifier = TrayNoticeNotifier()
    val notices =
        module {
            single<NoticeNotifier> { notifier }
            single<AppInstaller> { DesktopAppInstaller() }
        }
    startKoin { modules(appModules + platformAuthModule + platformDiscoveryModule + platformPreferencesModule + notices) }
    application {
        val windowState = rememberWindowState()
        val icon = remember { windowIcon() }
        val escape = remember { EscapeBack() }
        val tray = rememberTrayState()
        DisposableEffect(tray) {
            notifier.attach(tray)
            onDispose { notifier.attach(null) }
        }
        if (isTraySupported) Tray(icon, tray, tooltip = "Corvid")
        Window(onCloseRequest = ::exitApplication, title = "Corvid", state = windowState, icon = icon, onPreviewKeyEvent = escape::onKey) {
            escape.Attach()
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

private fun windowIcon() =
    Thread.currentThread().contextClassLoader.getResourceAsStream("corvid-512.png")!!.use { ImageIO.read(it) }.toPainter()
