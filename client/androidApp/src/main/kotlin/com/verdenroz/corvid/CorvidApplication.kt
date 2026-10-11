package com.verdenroz.corvid

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import com.verdenroz.corvid.app.NewMailNotifier
import com.verdenroz.corvid.app.NoticeNotifier
import com.verdenroz.corvid.app.NotificationPermission
import com.verdenroz.corvid.app.di.appModules
import com.verdenroz.corvid.core.auth.platformAuthModule
import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.discovery.platformDiscoveryModule
import com.verdenroz.corvid.core.keyring.KeyHolder
import com.verdenroz.corvid.core.prefs.platformPreferencesModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module

class CorvidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val notices =
            module {
                single<NoticeNotifier> { AndroidNoticeNotifier(androidContext()) }
                single<NewMailNotifier> { AndroidNewMailNotifier(androidContext()) }
                single { AndroidNotificationPermission(androidContext()) } bind NotificationPermission::class
                single<AppInstaller> { AndroidAppInstaller(androidContext()) }
            }
        val koin =
            startKoin {
                androidLogger()
                androidContext(this@CorvidApplication)
                modules(appModules + platformAuthModule + platformDiscoveryModule + platformPreferencesModule + notices)
            }.koin
        val keyHolder = koin.get<KeyHolder>()
        registerNetworkLossTrigger(keyHolder)
        registerScreenOffTrigger(keyHolder)
    }

    // A lost network drops the key immediately, with no grace timer.
    private fun registerNetworkLossTrigger(keyHolder: KeyHolder) {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) = keyHolder.drop()
            },
        )
    }

    // ACTION_SCREEN_OFF is implicit and unregisterable from the manifest since API 26.
    private fun registerScreenOffTrigger(keyHolder: KeyHolder) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = keyHolder.drop()
            }
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
}
