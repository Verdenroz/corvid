package com.verdenroz.corvid.app.di

import com.verdenroz.corvid.app.AppLockViewModel
import com.verdenroz.corvid.app.BackgroundDropController
import com.verdenroz.corvid.app.IdleTimeoutController
import com.verdenroz.corvid.app.UnlockedScopeHolder
import com.verdenroz.corvid.core.common.di.applicationScopeQualifier
import com.verdenroz.corvid.core.keyring.KeyHolder
import com.verdenroz.corvid.core.prefs.DevicePreferences
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appLockModule =
    module {
        single { UnlockedScopeHolder(koin = getKoin()) }
        single {
            IdleTimeoutController(
                scope = get(applicationScopeQualifier),
                onIdle = get<KeyHolder>()::drop,
                timeout = { get<DevicePreferences>().settings.value.autoLock.timeout },
            )
        }
        single { BackgroundDropController(scope = get(applicationScopeQualifier), onTimeout = get<KeyHolder>()::drop) }
        viewModelOf(::AppLockViewModel)
    }
