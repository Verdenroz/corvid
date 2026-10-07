package com.verdenroz.corvid.core.domain.di

import com.verdenroz.corvid.core.domain.DiscoverAccount
import com.verdenroz.corvid.core.domain.SignInUseCase
import org.koin.dsl.module

val domainModule =
    module {
        factory {
            SignInUseCase(authFlowFactory = get(), redirectUriProvider = get(), tokenSource = get(), authLatch = get())
        }
        factory { DiscoverAccount(srvLookup = get(), documents = get()) }
    }
