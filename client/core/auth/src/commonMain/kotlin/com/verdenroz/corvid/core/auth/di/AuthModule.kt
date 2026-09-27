package com.verdenroz.corvid.core.auth.di

import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.common.di.applicationScopeQualifier
import org.koin.dsl.module

val authModule =
    module {
        single { TokenSource(authLatch = get(), scope = get(applicationScopeQualifier)) }
    }
