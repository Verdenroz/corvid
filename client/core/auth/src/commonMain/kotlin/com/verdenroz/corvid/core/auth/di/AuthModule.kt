package com.verdenroz.corvid.core.auth.di

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.HttpAuthApi
import com.verdenroz.corvid.core.auth.PasskeyRepository
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.common.di.applicationScopeQualifier
import org.koin.dsl.module

val authModule =
    module {
        single<AuthApi> { HttpAuthApi(httpClient = get()) }
        single { TokenSource(authLatch = get(), scope = get(applicationScopeQualifier), api = get()) }
        scope<UnlockedScopeMarker> {
            scoped { PasskeyRepository(jmapClient = get()) }
        }
    }
