package com.verdenroz.corvid

import android.app.Application
import com.verdenroz.corvid.app.di.appModules
import com.verdenroz.corvid.core.auth.platformAuthModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class CorvidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@CorvidApplication)
            modules(appModules + platformAuthModule)
        }
    }
}
